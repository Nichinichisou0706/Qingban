package io.github.ming.qingban.domain

import org.junit.Test
import org.junit.Assert.*

class MusicLinksTest {
    @Test fun sharedTextAndHashUrlsAreParsed() { assertEquals("123",MusicLinks.neteaseId("分享一首歌曲 https://music.163.com/#/song?id=123&userid=1")) }
    @Test fun MobileLinksAreParsed() { assertEquals("123",MusicLinks.neteaseId("https://y.music.163.com/m/song?id=123")) }
    @Test fun attackerHostCannotSupplySongId() { assertNull(MusicLinks.neteaseId("https://music.163.com.evil.test/song?id=123")); assertNull(MusicLinks.neteaseId("https://music.163.com@evil.test/song?id=123")) }
    @Test fun playlistsAreNotMistakenForSongs() { assertNull(MusicLinks.neteaseId("https://music.163.com/playlist?id=123")) }
    @Test fun malformedIdsAreRejected() { assertNull(MusicLinks.neteaseId("https://music.163.com/song?id=../../file")); assertTrue(runCatching { MusicLinks.publicStream("../file") }.isFailure) }
    @Test fun shortSongShareIsCapturedForPlatformResolution() { assertEquals("https://163cn.tv/AbCd",MusicLinks.sharedUrl("分享《歌曲》 http://163cn.tv/AbCd")); assertEquals("歌曲",MusicLinks.title("分享《歌曲》","123")); assertNull(MusicLinks.sharedUrl("https://163cn.tv.evil.test/AbCd")) }
}
