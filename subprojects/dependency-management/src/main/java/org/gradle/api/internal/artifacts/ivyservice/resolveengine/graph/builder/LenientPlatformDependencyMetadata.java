/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.builder;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.gradle.api.artifacts.VersionConstraint;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ComponentSelector;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.internal.attributes.AttributesSchemaInternal;
import org.gradle.api.internal.attributes.ImmutableAttributes;
import org.gradle.internal.component.external.model.DefaultConfigurationMetadata;
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier;
import org.gradle.internal.component.external.model.DefaultModuleComponentSelector;
import org.gradle.internal.component.external.model.ModuleComponentArtifactMetadata;
import org.gradle.internal.component.external.model.ModuleDependencyMetadata;
import org.gradle.internal.component.external.model.VariantMetadataRules;
import org.gradle.internal.component.model.ComponentResolveMetadata;
import org.gradle.internal.component.model.ConfigurationMetadata;
import org.gradle.internal.component.model.DependencyMetadata;
import org.gradle.internal.component.model.ExcludeMetadata;
import org.gradle.internal.component.model.ForcingDependencyMetadata;
import org.gradle.internal.component.model.IvyArtifactName;
import org.gradle.internal.component.model.LocalComponentDependencyMetadata;

import java.util.Collections;
import java.util.List;
import java.util.Set;

class LenientPlatformDependencyMetadata implements ModuleDependencyMetadata, ForcingDependencyMetadata {
    private final ResolveState resolveState;
    private final NodeState from;
    private final ModuleComponentSelector cs;
    private final ModuleComponentIdentifier componentId;
    private final ComponentIdentifier platformId; // just for reporting
    private final boolean force;

    LenientPlatformDependencyMetadata(ResolveState resolveState, NodeState from, ModuleComponentSelector cs, ModuleComponentIdentifier componentId, ComponentIdentifier platformId, boolean force) {
        this.resolveState = resolveState;
        this.from = from;
        this.cs = cs;
        this.componentId = componentId;
        this.platformId = platformId;
        this.force = force;
    }

    @Override
    public ModuleComponentSelector getSelector() {
        return cs;
    }

    @Override
    public ModuleDependencyMetadata withRequestedVersion(VersionConstraint requestedVersion) {
        return this;
    }

    @Override
    public ModuleDependencyMetadata withReason(String reason) {
        return this;
    }

    @Override
    public List<ConfigurationMetadata> selectConfigurations(ImmutableAttributes consumerAttributes, ComponentResolveMetadata targetComponent, AttributesSchemaInternal consumerSchema) {
        if (targetComponent instanceof LenientPlatformResolveMetadata) {
            LenientPlatformResolveMetadata platformMetadata = (LenientPlatformResolveMetadata) targetComponent;
            return Collections.<ConfigurationMetadata>singletonList(new LenientPlatformConfigurationMetadata(platformMetadata.getPlatformState(), platformId));
        }
        // the target component exists, so we need to fallback to the traditional selection process
        return new LocalComponentDependencyMetadata(componentId, cs, null, ImmutableAttributes.EMPTY, ImmutableAttributes.EMPTY, null, Collections.<IvyArtifactName>emptyList(), Collections.<ExcludeMetadata>emptyList(), false, false, true, false, null).selectConfigurations(consumerAttributes, targetComponent, consumerSchema);
    }

    @Override
    public List<ExcludeMetadata> getExcludes() {
        return Collections.emptyList();
    }

    @Override
    public List<IvyArtifactName> getArtifacts() {
        return Collections.emptyList();
    }

    @Override
    public DependencyMetadata withTarget(ComponentSelector target) {
        return this;
    }

    @Override
    public boolean isChanging() {
        return false;
    }

    @Override
    public boolean isTransitive() {
        return true;
    }

    @Override
    public boolean isPending() {
        return true;
    }

    @Override
    public String getReason() {
        return "belongs to platform " + platformId;
    }

    @Override
    public String toString() {
        return "virtual metadata for " + componentId;
    }

    @Override
    public boolean isForce() {
        return force;
    }

    private class LenientPlatformConfigurationMetadata extends DefaultConfigurationMetadata {

        private final VirtualPlatformState platformState;
        private final ComponentIdentifier platformId;

        public LenientPlatformConfigurationMetadata(VirtualPlatformState platform, ComponentIdentifier platformId) {
            super(componentId, "default", true, false, ImmutableList.of("default"), ImmutableList.<ModuleComponentArtifactMetadata>of(), VariantMetadataRules.noOp(), ImmutableList.<ExcludeMetadata>of(), ImmutableAttributes.EMPTY);
            this.platformState = platform;
            this.platformId = platformId;
        }

        @Override
        public List<? extends DependencyMetadata> getDependencies() {
            List<DependencyMetadata> result = null;
            List<String> candidateVersions = platformState.getCandidateVersions();
            Set<ModuleResolveState> modules = platformState.getParticipatingModules();
            for (ModuleResolveState module : modules) {
                ComponentState selected = module.getSelected();
                if (selected != null) {
                    String componentVersion = selected.getId().getVersion();
                    for (String target : candidateVersions) {
                        ModuleComponentIdentifier leafId = DefaultModuleComponentIdentifier.newId(module.getId(), target);
                        ModuleComponentSelector leafSelector = DefaultModuleComponentSelector.newSelector(module.getId(), target);
                        ComponentIdentifier platformId = platformState.getSelectedPlatformId();
                        if (platformId == null) {
                            // Not sure this can happen, unless in error state
                            platformId = this.platformId;
                        }
                        if (!componentVersion.equals(target)) {
                            // We will only add dependencies to the leaves if there is such a published module
                            PotentialEdge potentialEdge = PotentialEdge.of(resolveState, from, leafId, leafSelector, platformId, platformState.isForced());
                            if (potentialEdge.metadata != null) {
                                result = registerPlatformEdge(result, modules, leafId, leafSelector, platformId, platformState.isForced());
                                break;
                            }
                        } else {
                            // at this point we know the component exists
                            result = registerPlatformEdge(result, modules, leafId, leafSelector, platformId, platformState.isForced());
                            break;
                        }
                    }
                }
            }
            return result == null ? Collections.<DependencyMetadata>emptyList() : result;
        }

        private List<DependencyMetadata> registerPlatformEdge(List<DependencyMetadata> result, Set<ModuleResolveState> modules, ModuleComponentIdentifier leafId, ModuleComponentSelector leafSelector, ComponentIdentifier platformId, boolean force) {
            if (result == null) {
                result = Lists.newArrayListWithExpectedSize(modules.size());
            }
            result.add(new LenientPlatformDependencyMetadata(
                resolveState,
                from,
                leafSelector,
                leafId,
                platformId,
                force
            ));
            return result;
        }
    }
}
