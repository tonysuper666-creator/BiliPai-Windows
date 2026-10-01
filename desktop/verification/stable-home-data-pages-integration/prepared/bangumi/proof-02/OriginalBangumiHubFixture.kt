package com.android.purebilibili.feature.bangumi
private var assertionCount=0
internal fun assertTrue(value:Boolean){assertionCount++;check(value)}
internal fun assertFalse(value:Boolean){assertionCount++;check(!value)}
internal fun assertNull(value:Any?){assertionCount++;check(value==null)}
internal fun assertNotEquals(expected:Any?,actual:Any?){assertionCount++;check(expected!=actual)}
internal fun assertEquals(expected:Any?,actual:Any?){assertionCount++;check(expected==actual){"$expected != $actual"}}
internal fun assertEquals(expected:Float,actual:Float,delta:Float){assertionCount++;check(kotlin.math.abs(expected-actual)<=delta)}
internal fun assertEquals(expected:Double,actual:Double,delta:Double){assertionCount++;check(kotlin.math.abs(expected-actual)<=delta)}
fun main(){
 BangumiHubPolicyTest().`initial types map to the two BiliPai channels`()
 BangumiHubPolicyTest().`back handling prioritizes selection and nested pages`()
 BangumiHubPolicyTest().`server conditions produce default index parameters`()
 BangumiHubPolicyTest().`bangumi and guochuang timelines merge by date and deduplicate episodes`()
 BangumiHubPolicyTest().`timeline labels include visible dates and today marker`()
 BangumiHubPolicyTest().`timeline ranges stay within the server supported seven day window`()
 BangumiHubPolicyTest().`timeline episode metadata prefers the matching cover and update state`()
 BangumiHubPolicyTest().`selection toggles valid ids only`()
 BangumiHubPolicyTest().`index categories produce BiliPai query targets`()
 BangumiHubPolicyTest().`search categories preserve channel scope and filter exact season types`()
 BangumiHubPolicyTest().`pagination de-duplicates and reset drops old page`()
 BangumiHubPolicyTest().`failed batch mutation preserves selection`()
 BangumiHubBlurPolicyTest().initialSkeletonBlocksCaptureButRetainedContentRefreshDoesNot()
 BangumiHubBlurPolicyTest().hiddenTimelineAndLoggedOutFollowSkeletonsDoNotBlockCapture()
 BangumiHubBlurPolicyTest().inactivePageLoadingDoesNotBlockTheCurrentPage()
 BangumiHubBlurPolicyTest().searchWithoutItemsBlocksCaptureEvenDuringLoadMore()
 MyFollowPolicyTest().`anime and guochuang should map to bangumi follow type`()
 MyFollowPolicyTest().`movie tv documentary and variety should map to cinema follow type`()
 MyFollowPolicyTest().`explicit request type should override current type`()
 MyFollowPolicyTest().`null request type should keep current type`()
 MyFollowPolicyTest().`follow item lazy keys stay unique when api returns duplicate zero season id`()
 MyFollowPolicyTest().`follow item lazy key keeps stable business id when season id exists`()
 MyFollowPolicyTest().`index item lazy keys stay unique when api returns duplicate zero season id`()
 MyFollowPolicyTest().`index item lazy key keeps stable business id when season id exists`()
 MyFollowPolicyTest().`search item lazy keys stay unique when api returns duplicate zero season id`()
 MyFollowPolicyTest().`search item lazy key keeps stable business id when season id exists`()
 MyFollowPolicyTest().`timeline episode lazy keys stay unique when api returns duplicate zero episode id`()
 MyFollowPolicyTest().`timeline episode lazy key keeps stable business id when episode id exists`()
 println("{\"passed\":true,\"adaptedOriginalPolicyMethods\":28,\"adaptedOriginalAssertions\":"+assertionCount+",\"actualMainOrRootAccepted\":false}")
}
