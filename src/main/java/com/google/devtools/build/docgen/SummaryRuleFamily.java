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

package com.google.devtools.build.docgen;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ListMultimap;
import com.google.devtools.build.docgen.DocgenConsts.RuleType;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;

import java.util.List;

/**
 * Helper class for representing a rule family in the rule summary table template.
 *
 * <p>The rules are separated into categories by rule class: binary, library, test, and
 * other.
 */
@Immutable
public class SummaryRuleFamily {
  private final String name;
  private final ImmutableList<RuleDocumentation> binaryRules;
  private final ImmutableList<RuleDocumentation> libraryRules;
  private final ImmutableList<RuleDocumentation> testRules;
  private final ImmutableList<RuleDocumentation> otherRules1;
  private final ImmutableList<RuleDocumentation> otherRules2;

  SummaryRuleFamily(ListMultimap<RuleType, RuleDocumentation> ruleTypeMap, String name) {
    this.name = name;
    this.binaryRules = ImmutableList.copyOf(ruleTypeMap.get(RuleType.BINARY));
    this.libraryRules = ImmutableList.copyOf(ruleTypeMap.get(RuleType.LIBRARY));
    this.testRules = ImmutableList.copyOf(ruleTypeMap.get(RuleType.TEST));

    final ImmutableList<RuleDocumentation> otherRules =
        ImmutableList.copyOf(ruleTypeMap.get(RuleType.OTHER));
    if (otherRules.size() >= 4) {
      this.otherRules1 = ImmutableList.copyOf(otherRules.subList(0, otherRules.size() / 2));
      this.otherRules2 =
          ImmutableList.copyOf(otherRules.subList(otherRules.size() / 2, otherRules.size()));
    } else {
      this.otherRules1 = otherRules;
      this.otherRules2 = ImmutableList.of();
    }
  }

  public String getName() {
    return name;
  }

  public List<RuleDocumentation> getBinaryRules() {
    return binaryRules;
  }

  public List<RuleDocumentation> getLibraryRules() {
    return libraryRules;
  }

  public List<RuleDocumentation> getTestRules() {
    return testRules;
  }

  public List<RuleDocumentation> getOtherRules1() {
    return otherRules1;
  }

  public List<RuleDocumentation> getOtherRules2() {
    return otherRules2;
  }
}
