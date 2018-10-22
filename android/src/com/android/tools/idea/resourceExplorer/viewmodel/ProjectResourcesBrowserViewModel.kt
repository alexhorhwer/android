/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.tools.idea.resourceExplorer.viewmodel

import com.android.ide.common.rendering.api.ResourceValue
import com.android.ide.common.repository.GradleCoordinate
import com.android.ide.common.resources.ResourceItem
import com.android.ide.common.resources.ResourceResolver
import com.android.ide.common.resources.configuration.FolderConfiguration
import com.android.resources.ResourceType
import com.android.tools.idea.configurations.ConfigurationManager
import com.android.tools.idea.configurations.ResourceResolverCache
import com.android.tools.idea.model.MergedManifest
import com.android.tools.idea.res.ResourceNotificationManager
import com.android.tools.idea.res.ResourceRepositoryManager
import com.android.tools.idea.res.aar.AarResourceRepository
import com.android.tools.idea.res.resolveDrawable
import com.android.tools.idea.resourceExplorer.model.DesignAsset
import com.android.tools.idea.resourceExplorer.model.DesignAssetSet
import com.android.tools.idea.resourceExplorer.plugin.DesignAssetRendererManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import org.jetbrains.android.facet.AndroidFacet
import java.awt.Dimension
import java.awt.Image
import java.util.concurrent.CompletableFuture
import kotlin.properties.Delegates

private val LOG = Logger.getInstance(ProjectResourcesBrowserViewModel::class.java)
private val SUPPORTED_RESOURCES = arrayOf(ResourceType.DRAWABLE, ResourceType.COLOR, ResourceType.SAMPLE_DATA)

/**
 * ViewModel for [com.android.tools.idea.resourceExplorer.view.ResourceExplorerView]
 * to manage resources in the provided [facet].
 */
class ProjectResourcesBrowserViewModel(
  facet: AndroidFacet
) : Disposable {
  /**
   * callback called when the resource model have change. This happen when the facet is changed.
   */
  var resourceChangedCallback: (() -> Unit)? = null

  var facet by Delegates.observable(facet) { _, oldFacet, newFacet -> facetUpdated(newFacet, oldFacet) }

  private var resourceVersion: ResourceNotificationManager.ResourceVersion? = null

  private val resourceNotificationManager: ResourceNotificationManager = ResourceNotificationManager(facet.module.project)

  private val dataManager = ResourceDataManager(facet)

  private val resourceNotificationListener = ResourceNotificationManager.ResourceChangeListener { reason ->
    if (reason.size == 1 && reason.contains(ResourceNotificationManager.Reason.EDIT)) {
      // We don't want to update all resources for every resource file edit.
      // TODO cache the resources, notify the view to only update the rendering of the edited resource.
      return@ResourceChangeListener
    }
    val currentVersion = resourceNotificationManager.getCurrentVersion(facet, null, null)
    if (resourceVersion == currentVersion) {
      return@ResourceChangeListener
    }
    resourceVersion = currentVersion
    resourceChangedCallback?.invoke()
  }

  var resourceResolver = createResourceResolver(facet)

  /**
   * The index in [resourceTypes] of the resource type being used.
   */
  var resourceTypeIndex = 0
    set(value) {
      if (value in 0 until resourceTypes.size) {
        field = value
        resourceChangedCallback?.invoke()
      }
    }

  val resourceTypes: Array<ResourceType> get() = SUPPORTED_RESOURCES

  init {
    subscribeListener(facet)
  }

  private fun facetUpdated(newFacet: AndroidFacet, oldFacet: AndroidFacet) {
    resourceResolver = createResourceResolver(newFacet)
    unsubscribeListener(oldFacet)
    subscribeListener(newFacet)
    dataManager.facet = newFacet
    resourceChangedCallback?.invoke()
  }

  private fun subscribeListener(facet: AndroidFacet) {
    ResourceNotificationManager.getInstance(facet.module.project)
      .addListener(resourceNotificationListener, facet, null, null)
  }

  private fun unsubscribeListener(oldFacet: AndroidFacet) {
    ResourceNotificationManager.getInstance(oldFacet.module.project)
      .removeListener(resourceNotificationListener, oldFacet, null, null)
  }

  /**
   * Returns a preview of the [DesignAsset].
   */
  fun getDrawablePreview(dimension: Dimension, designAsset: DesignAsset): CompletableFuture<out Image?> {
    val resolveValue = designAsset.resolveValue() ?: return CompletableFuture.completedFuture(null)
    val file = resourceResolver.resolveDrawable(resolveValue, facet.module.project)
               ?: designAsset.file
    return DesignAssetRendererManager.getInstance().getViewer(file)
      .getImage(file, facet.module, dimension)
  }

  private fun getModuleResources(type: ResourceType): List<ResourceItem> {
    val moduleRepository = ResourceRepositoryManager.getModuleResources(facet)
    return moduleRepository.namespaces
      .flatMap { namespace -> moduleRepository.getResources(namespace, type).values() }
      .sortedBy { it.name }
  }

  /**
   * Returns a map from the library name to its resource items
   */
  private fun getLibraryResources(type: ResourceType): List<Pair<String, List<ResourceItem>>> {
    val repoManager = ResourceRepositoryManager.getOrCreateInstance(facet)
    return repoManager.libraryResources
      .map { lib ->
        (userReadableLibraryName(lib)) to lib.namespaces
          .flatMap { namespace -> lib.getResources(namespace, type).values() }
          .sortedBy { it.name }
      }
  }

  private fun createResourceResolver(androidFacet: AndroidFacet): ResourceResolver {
    val configurationManager = ConfigurationManager.getOrCreateInstance(androidFacet)
    val manifest = MergedManifest.get(androidFacet)
    val theme = manifest.manifestTheme ?: manifest.getDefaultTheme(null, null, null)
    return ResourceResolverCache(configurationManager).getResourceResolver(
      configurationManager.target,
      theme,
      FolderConfiguration.createDefault()
    )
  }

  private fun DesignAsset.resolveValue(): ResourceValue? {
    val resourceItem = this.resourceItem
    val resolvedValue = resourceResolver.resolveResValue(resourceItem.resourceValue)
    if (resolvedValue == null) {
      LOG.warn("${resourceItem.name} couldn't be resolved")
    }
    return resolvedValue
  }

  fun getResourcesLists(): List<ResourceSection> {
    val resourceType = resourceTypes[resourceTypeIndex]
    val moduleResources = createResourceSection(resourceType, facet.module.name, getModuleResources(resourceType))
    val librariesResources = getLibraryResources(resourceType)
      .asSequence()
      .filter { (_, items) -> items.isNotEmpty() }
      .map { (libName, resourceItems) -> createResourceSection(resourceType, libName, resourceItems) }
      .toList()
    return listOf(moduleResources) + librariesResources
  }

  override fun dispose() {
    unsubscribeListener(facet)
  }

  fun getData(dataId: String?, selectedAssets: List<DesignAssetSet>): Any? {
    return dataManager.getData(dataId, selectedAssets)
  }
}

private fun createResourceSection(type: ResourceType,
                                  libraryName: String,
                                  resourceItems: List<ResourceItem>) =
  ResourceSection(type,
                  libraryName,
                  resourceItems.map { DesignAsset(it) }
                    .groupBy { it.name }
                    .map { (name, assets) -> DesignAssetSet(name, assets) })

data class ResourceSection(val type: ResourceType,
                           val libraryName: String = "",
                           val assets: List<DesignAssetSet>)

private fun userReadableLibraryName(lib: AarResourceRepository) = GradleCoordinate.parseCoordinateString(lib.libraryName)?.artifactId ?: ""
