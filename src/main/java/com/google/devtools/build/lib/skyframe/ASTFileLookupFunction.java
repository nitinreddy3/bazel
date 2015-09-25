// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.skyframe;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.packages.RuleClassProvider;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.syntax.BuildFileAST;
import com.google.devtools.build.lib.syntax.Mutability;
import com.google.devtools.build.lib.syntax.Runtime;
import com.google.devtools.build.lib.syntax.ValidationEnvironment;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

/**
 * A SkyFunction for {@link ASTFileLookupValue}s. Tries to locate a file and load it as a
 * syntax tree and cache the resulting {@link BuildFileAST}. If the file doesn't exist
 * the function doesn't fail but returns a specific NO_FILE ASTLookupValue.
 */
public class ASTFileLookupFunction implements SkyFunction {

  private abstract static class FileLookupResult {
    /** Returns whether the file lookup was successful. */
    public abstract boolean lookupSuccessful();

    /** If {@code lookupSuccessful()}, returns the {@link RootedPath} to the file. */
    public abstract RootedPath rootedPath();

    /** If {@code lookupSuccessful()}, returns the file's size, in bytes. */
    public abstract long fileSize();

    static FileLookupResult noFile() {
      return UnsuccessfulFileResult.INSTANCE;
    }

    static FileLookupResult file(RootedPath rootedPath, long fileSize) {
      return new SuccessfulFileResult(rootedPath, fileSize);
    }

    private static class SuccessfulFileResult extends FileLookupResult {
      private final RootedPath rootedPath;
      private final long fileSize;

      private SuccessfulFileResult(RootedPath rootedPath, long fileSize) {
        this.rootedPath = rootedPath;
        this.fileSize = fileSize;
      }

      @Override
      public boolean lookupSuccessful() {
        return true;
      }

      @Override
      public RootedPath rootedPath() {
        return rootedPath;
      }

      @Override
      public long fileSize() {
        return fileSize;
      }
    }

    private static class UnsuccessfulFileResult extends FileLookupResult {
      private static final UnsuccessfulFileResult INSTANCE = new UnsuccessfulFileResult();
      private UnsuccessfulFileResult() {
      }

      @Override
      public boolean lookupSuccessful() {
        return false;
      }

      @Override
      public RootedPath rootedPath() {
        throw new IllegalStateException("unsuccessful lookup");
      }

      @Override
      public long fileSize() {
        throw new IllegalStateException("unsuccessful lookup");
      }
    }
  }

  private final AtomicReference<PathPackageLocator> pkgLocator;
  private final RuleClassProvider ruleClassProvider;

  public ASTFileLookupFunction(AtomicReference<PathPackageLocator> pkgLocator,
      RuleClassProvider ruleClassProvider) {
    this.pkgLocator = pkgLocator;
    this.ruleClassProvider = ruleClassProvider;
  }

  @Override
  public SkyValue compute(SkyKey skyKey, Environment env) throws SkyFunctionException,
      InterruptedException {
    PackageIdentifier key = (PackageIdentifier) skyKey.argument();
    PathFragment astFilePathFragment = key.getPackageFragment();
    FileLookupResult lookupResult = getASTFile(env, key);
    if (lookupResult == null) {
      return null;
    }
    if (!lookupResult.lookupSuccessful()) {
      return ASTFileLookupValue.noFile();
    }
    BuildFileAST ast = null;
    Path path = lookupResult.rootedPath().asPath();
    long fileSize = lookupResult.fileSize();
    // Skylark files end with bzl.
    boolean parseAsSkylark = astFilePathFragment.getPathString().endsWith(".bzl");
    try {
      if (parseAsSkylark) {
        try (Mutability mutability = Mutability.create("validate")) {
            ast = BuildFileAST.parseSkylarkFile(path, fileSize, env.getListener(),
                new ValidationEnvironment(
                    ruleClassProvider.createSkylarkRuleClassEnvironment(
                        mutability,
                        env.getListener(),
                        // the two below don't matter for extracting the ValidationEnvironment:
                        /*astFileContentHashCode=*/null,
                        /*importMap=*/null)
                    .setupDynamic(Runtime.PKG_NAME, Runtime.NONE)));
        }
      } else {
        ast = BuildFileAST.parseBuildFile(path, fileSize, env.getListener(), false);
      }
    } catch (IOException e) {
        throw new ASTLookupFunctionException(new ErrorReadingSkylarkExtensionException(
            e.getMessage()), Transience.TRANSIENT);
    }
    return ASTFileLookupValue.withFile(ast);
  }

  private FileLookupResult getASTFile(Environment env, PackageIdentifier key)
      throws ASTLookupFunctionException {
    List<Path> candidateRoots;
    if (!key.getRepository().isDefault()) {
      RepositoryValue repository =
          (RepositoryValue) env.getValue(RepositoryValue.key(key.getRepository()));
      if (repository == null) {
        return null;
      }

      candidateRoots = ImmutableList.of(repository.getPath());
    } else {
      candidateRoots = pkgLocator.get().getPathEntries();
    }

    for (Path root : candidateRoots) {
      RootedPath rootedPath = RootedPath.toRootedPath(root, key.getPackageFragment());
      FileValue fileValue;
      try {
        fileValue = (FileValue) env.getValueOrThrow(FileValue.key(rootedPath),
            IOException.class, FileSymlinkException.class, InconsistentFilesystemException.class);
      } catch (IOException | FileSymlinkException e) {
        throw new ASTLookupFunctionException(new ErrorReadingSkylarkExtensionException(
            e.getMessage()), Transience.PERSISTENT);
      } catch (InconsistentFilesystemException e) {
        throw new ASTLookupFunctionException(e, Transience.PERSISTENT);
      }
      if (fileValue == null) {
        return null;
      }
      if (fileValue.isFile()) {
        return FileLookupResult.file(rootedPath, fileValue.getSize());
      }
    }
    return FileLookupResult.noFile();
  }

  @Nullable
  @Override
  public String extractTag(SkyKey skyKey) {
    return null;
  }

  private static final class ASTLookupFunctionException extends SkyFunctionException {
    private ASTLookupFunctionException(ErrorReadingSkylarkExtensionException e,
        Transience transience) {
      super(e, transience);
    }

    private ASTLookupFunctionException(InconsistentFilesystemException e, Transience transience) {
      super(e, transience);
    }
  }
}
