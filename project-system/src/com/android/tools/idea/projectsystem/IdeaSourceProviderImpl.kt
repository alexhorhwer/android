/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.tools.idea.projectsystem

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager

/**
 * An implementation of [IdeaSourceProvider] configured with a static set of file urls for each collection and the manifest but still
 * resolving them dynamically so that changes to the set of files that exist at any given moment are picked up.
 */
class IdeaSourceProviderImpl(
  override val name: String,
  override val manifestFileUrl: String,
  override val javaDirectoryUrls: Collection<String> = emptyList(),
  override val resourcesDirectoryUrls: Collection<String> = emptyList(),
  override val aidlDirectoryUrls: Collection<String> = emptyList(),
  override val renderscriptDirectoryUrls: Collection<String> = emptyList(),
  override val jniDirectoryUrls: Collection<String> = emptyList(),
  override val jniLibsDirectoryUrls: Collection<String> = emptyList(),
  override val resDirectoryUrls: Collection<String> = emptyList(),
  override val assetsDirectoryUrls: Collection<String> = emptyList(),
  override val shadersDirectoryUrls: Collection<String> = emptyList()
) : IdeaSourceProvider {
  @Volatile
  private var myManifestFile: VirtualFile? = null

  override val manifestFile: VirtualFile?
    get() {
      if (myManifestFile == null || !myManifestFile!!.isValid) {
        myManifestFile = VirtualFileManager.getInstance().findFileByUrl(manifestFileUrl)
      }

      return myManifestFile
    }

  override val javaDirectories: Collection<VirtualFile> get() = convertUrlSet(javaDirectoryUrls)

  override val resourcesDirectories: Collection<VirtualFile> get() = convertUrlSet(resourcesDirectoryUrls)

  override val aidlDirectories: Collection<VirtualFile> get() = convertUrlSet(aidlDirectoryUrls)

  override val renderscriptDirectories: Collection<VirtualFile> get() = convertUrlSet(renderscriptDirectoryUrls)

  override val jniDirectories: Collection<VirtualFile> get() = convertUrlSet(jniDirectoryUrls)

  override val jniLibsDirectories: Collection<VirtualFile> get() = convertUrlSet(jniLibsDirectoryUrls)

  // TODO: Perform some caching; this method gets called a lot!
  override val resDirectories: Collection<VirtualFile> get() = convertUrlSet(resDirectoryUrls)

  override val assetsDirectories: Collection<VirtualFile> get() = convertUrlSet(assetsDirectoryUrls)

  override val shadersDirectories: Collection<VirtualFile> get() = convertUrlSet(shadersDirectoryUrls)

  /** Convert a set of IDEA file urls into a set of equivalent virtual files  */
  private fun convertUrlSet(fileUrls: Collection<String>): Collection<VirtualFile> {
    val fileManager = VirtualFileManager.getInstance()
    return fileUrls.mapNotNull { fileManager.findFileByUrl(it) }
  }
}