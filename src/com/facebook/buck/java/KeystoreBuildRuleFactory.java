/*
 * Copyright 2013-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.java;

import com.facebook.buck.java.KeystoreRule.Builder;
import com.facebook.buck.parser.AbstractTestRuleFactory;
import com.facebook.buck.parser.BuildRuleFactoryParams;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;

public class KeystoreBuildRuleFactory extends AbstractTestRuleFactory<KeystoreRule.Builder> {

  @Override
  protected Builder newBuilder(AbstractBuildRuleBuilderParams params) {
    return KeystoreRule.newKeystoreBuilder(params);
  }

  @Override
  protected void amendBuilder(Builder builder, BuildRuleFactoryParams params)
      throws NoSuchBuildTargetException {
    builder.setStore(params.getRequiredFileAsPathRelativeToProjectRoot("store"));
    builder.setProperties(params.getRequiredFileAsPathRelativeToProjectRoot("properties"));
  }

}