package com.mts.tikmod.hooks

import android.content.Context
import com.mts.tikmod.discovery.Fingerprints
import com.mts.tikmod.utils.StoryTracker
import com.mts.tikmod.utils.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

/**
 * Feeds [StoryTracker] with the stories TikTok loads and watches. Every target is a Gson model whose class and
 * member names come from the server JSON, so they aren't obfuscated (checked on TikTok 47.1.4):
 * - `UserStory.getStories()`: one creator's story list (with hasMoreBefore/After, totalCount, isFromRemote)
 * - `Aweme.getIsTikTokStory()`: true on any story item, wherever it's shown
 * - `Story.setViewed(true)`: TikTok marking a story as watched on the phone
 *
 * Hiding story views lives in [PresenceHook] (it blocks the report request).
 */
class StoryHook : BaseHook() {
    override val name: String = "Stories (archive / auto-save / deleted)"

    private companion object {
        const val USER_STORY = "com.ss.android.ugc.aweme.feed.model.story.UserStory"
        const val STORY = "com.ss.android.ugc.aweme.feed.model.story.Story"
    }

    override fun apply(classLoader: ClassLoader, context: Context?) {
        tryHook {
            var hooked = 0
            val userStory = XposedHelpers.findClassIfExists(USER_STORY, classLoader)
            hooked += hookAllMethodsSafely(userStory, "getStories", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching { StoryTracker.onUserStoryList(param.thisObject, param.result as? List<*>) }
                }
            })

            val aweme = XposedHelpers.findClassIfExists(Fingerprints.AWEME_CLASS, classLoader)
            hooked += hookAllMethodsSafely(aweme, "getIsTikTokStory", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.result == true) runCatching { StoryTracker.remember(param.thisObject) }
                }
            })

            val story = XposedHelpers.findClassIfExists(STORY, classLoader)
            hooked += hookAllMethodsSafely(story, "setViewed", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args.firstOrNull() != true) return
                    val was = runCatching { XposedHelpers.getBooleanField(param.thisObject, "viewed") }.getOrDefault(false)
                    if (!was) runCatching { StoryTracker.onStoryModelViewed(param.thisObject) }
                }
            })

            status = status.copy(foundClass = listOfNotNull(userStory?.simpleName, aweme?.simpleName, story?.simpleName).joinToString(" + ") + " ($hooked)")
            XLog.log("Stories: $hooked hooks (UserStory=${userStory != null}, Aweme=${aweme != null}, Story=${story != null})")
            if (hooked == 0) throw IllegalStateException("no story model found")
        }
    }
}
