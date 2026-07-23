// Copyright 2018 Dolphin Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#include <algorithm>
#include <iterator>
#include <memory>
#include <vector>

#include <jni.h>

#include "Common/Logging/Log.h"
#include "Core/Config/MainSettings.h"
#include "Core/WiiForwarder.h"
#include "DiscIO/Enums.h"
#include "UICommon/GameFileCache.h"
#include "jni/AndroidCommon/AndroidCommon.h"
#include "jni/AndroidCommon/IDCache.h"
#include "jni/GameList/GameFile.h"

static UICommon::GameFileCache* GetPointer(JNIEnv* env, jobject obj)
{
  return reinterpret_cast<UICommon::GameFileCache*>(
      env->GetLongField(obj, IDCache::GetGameFileCachePointer()));
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_org_dolphinemu_dolphinemu_model_GameFileCache_newGameFileCache(JNIEnv* env, jclass)
{
  return reinterpret_cast<jlong>(new UICommon::GameFileCache());
}

JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_model_GameFileCache_finalize(JNIEnv* env,
                                                                                   jobject obj)
{
  delete GetPointer(env, obj);
}

JNIEXPORT jobjectArray JNICALL Java_org_dolphinemu_dolphinemu_model_GameFileCache_getAllGamePaths(
    JNIEnv* env, jclass, jobjectArray folder_paths, jboolean recursive_scan)
{
  const std::vector<std::string> paths = JStringArrayToVector(env, folder_paths);
  std::vector<std::string_view> path_views;
  std::ranges::copy(paths, std::back_inserter(path_views));
  return SpanToJStringArray(env, UICommon::FindAllGamePaths(path_views, recursive_scan));
}

JNIEXPORT jobjectArray JNICALL
Java_org_dolphinemu_dolphinemu_model_GameFileCache_getIsoPaths(JNIEnv* env, jclass)
{
  return SpanToJStringArray(env, Config::GetIsoPaths());
}

JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_model_GameFileCache_setIsoPaths(
    JNIEnv* env, jclass, jobjectArray paths)
{
  Config::SetIsoPaths(JStringArrayToVector(env, paths));
}

JNIEXPORT jint JNICALL Java_org_dolphinemu_dolphinemu_model_GameFileCache_getSize(JNIEnv* env,
                                                                                  jobject obj)
{
  return static_cast<jint>(GetPointer(env, obj)->GetSize());
}

JNIEXPORT jobjectArray JNICALL
Java_org_dolphinemu_dolphinemu_model_GameFileCache_getAllGames(JNIEnv* env, jobject obj)
{
  const UICommon::GameFileCache* ptr = GetPointer(env, obj);
  const jobjectArray array =
      env->NewObjectArray(static_cast<jsize>(ptr->GetSize()), IDCache::GetGameFileClass(), nullptr);
  jsize i = 0;
  GetPointer(env, obj)->ForEach([env, array, &i](const auto& game_file) {
    jobject j_game_file = GameFileToJava(env, game_file);
    env->SetObjectArrayElement(array, i++, j_game_file);
    env->DeleteLocalRef(j_game_file);
  });
  return array;
}

JNIEXPORT jobject JNICALL Java_org_dolphinemu_dolphinemu_model_GameFileCache_addOrGet(JNIEnv* env,
                                                                                      jobject obj,
                                                                                      jstring path)
{
  bool cache_changed = false;
  auto game = GetPointer(env, obj)->AddOrGet(GetJString(env, path), &cache_changed);
  if (cache_changed && game && game->GetPlatform() == DiscIO::Platform::WiiDisc &&
      !WiiForwarder::IsForwarderInstalled(game->GetFilePath()))
  {
    if (WiiForwarder::InstallForwarder(game->GetFilePath(), /*silent=*/true))
    {
      INFO_LOG_FMT(CORE, "Auto-installed Wii Menu forwarder for '{}'", game->GetFilePath());
    }
  }
  return GameFileToJava(env, game);
}

JNIEXPORT jboolean JNICALL Java_org_dolphinemu_dolphinemu_model_GameFileCache_update(
    JNIEnv* env, jobject obj, jobjectArray game_paths)
{
  return GetPointer(env, obj)->Update(
      JStringArrayToVector(env, game_paths),
      [](const std::shared_ptr<const UICommon::GameFile>& game) {
        if (game->GetPlatform() == DiscIO::Platform::WiiDisc &&
            !WiiForwarder::IsForwarderInstalled(game->GetFilePath()))
        {
          if (WiiForwarder::InstallForwarder(game->GetFilePath(), /*silent=*/true))
          {
            INFO_LOG_FMT(CORE, "Auto-installed Wii Menu forwarder for '{}'", game->GetFilePath());
          }
        }
      },
      [](const std::string& path) {
        if (WiiForwarder::IsForwarderInstalled(path))
        {
          const auto forwarders = WiiForwarder::GetInstalledForwarders();
          for (const auto& [tid, disc_path] : forwarders)
          {
            if (disc_path == path)
            {
              WiiForwarder::UninstallForwarder(tid);
              INFO_LOG_FMT(CORE, "Auto-removed Wii Menu forwarder for '{}'", path);
              break;
            }
          }
        }
      });
}

JNIEXPORT jboolean JNICALL
Java_org_dolphinemu_dolphinemu_model_GameFileCache_updateAdditionalMetadata(JNIEnv* env,
                                                                            jobject obj)
{
  return GetPointer(env, obj)->UpdateAdditionalMetadata();
}

JNIEXPORT jboolean JNICALL Java_org_dolphinemu_dolphinemu_model_GameFileCache_load(JNIEnv* env,
                                                                                   jobject obj)
{
  return GetPointer(env, obj)->Load();
}

JNIEXPORT jboolean JNICALL Java_org_dolphinemu_dolphinemu_model_GameFileCache_save(JNIEnv* env,
                                                                                   jobject obj)
{
  return GetPointer(env, obj)->Save();
}
}
