package com.interstellar.proxy.data.net

import org.junit.Test

/**
 * FlClash-style Content-Disposition parsing — CN panels mostly send the
 * RFC 5987 star form with a percent-encoded UTF-8 name.
 */
class SubscriptionFetcherTest {

    @Test
    fun `rfc5987 filename-star decodes percent-encoded utf8`() {
        val header = "attachment; filename*=UTF-8''%E8%89%AF%E5%BF%83%E4%BA%91"
        check(SubscriptionFetcher.parseDispositionName(header) == "良心云")
    }

    @Test
    fun `plain filename decodes and strips extension`() {
        check(SubscriptionFetcher.parseDispositionName("attachment; filename=\"my-sub.yaml\"") == "my-sub")
        check(
            SubscriptionFetcher.parseDispositionName(
                "attachment; filename=\"%E6%9C%BA%E5%9C%BA%E5%90%8D.txt\"",
            ) == "机场名",
        )
    }

    @Test
    fun `star form wins over plain filename`() {
        val header = "attachment; filename=\"fallback.yaml\"; filename*=UTF-8''%E6%B5%8B%E8%AF%95"
        check(SubscriptionFetcher.parseDispositionName(header) == "测试")
    }

    @Test
    fun `literal plus survives decoding`() {
        check(SubscriptionFetcher.parseDispositionName("attachment; filename=\"a+b.yaml\"") == "a+b")
    }

    @Test
    fun `garbage headers yield null`() {
        check(SubscriptionFetcher.parseDispositionName(null) == null)
        check(SubscriptionFetcher.parseDispositionName("") == null)
        check(SubscriptionFetcher.parseDispositionName("inline") == null)
        // empty after cleaning
        check(SubscriptionFetcher.parseDispositionName("attachment; filename=\".yaml\"") == null)
    }

    // ---- Subscription-Userinfo: drives the quota / expiry display ----

    @Test
    fun `userinfo parses upload download total expire`() {
        val fields = SubscriptionFetcher.parseSubscriptionUserinfo(
            "upload=34359738368; download=107374182400; total=536870912000; expire=1850000000",
        )
        check(fields["upload"] == 34359738368L)
        check(fields["download"] == 107374182400L)
        check(fields["total"] == 536870912000L)
        check(fields["expire"] == 1850000000L)

        // used = upload + download, remaining = max(total - used, 0)
        val used = fields.getValue("upload") + fields.getValue("download")
        check(used == 141733920768L)
        check(fields.getValue("total") - used == 395136991232L)
    }

    @Test
    fun `userinfo tolerates spacing, case and extra keys`() {
        val fields = SubscriptionFetcher.parseSubscriptionUserinfo(
            " Upload = 1 ; DOWNLOAD=2;total=3;expire=4;reset_day=7;plan=pro ",
        )
        check(fields["upload"] == 1L)
        check(fields["download"] == 2L)
        check(fields["total"] == 3L)
        check(fields["expire"] == 4L)
        check(fields["reset_day"] == 7L)
    }

    @Test
    fun `userinfo drops malformed pairs instead of failing`() {
        // non-numeric value, bare key without '=', empty value, stray semicolons
        val fields = SubscriptionFetcher.parseSubscriptionUserinfo(
            "upload=abc; download; total=; ; expire=1750000000",
        )
        check(fields["upload"] == null)
        check(fields["download"] == null)
        check(fields["total"] == null)
        check(fields["expire"] == 1750000000L)
    }

    @Test
    fun `userinfo with no usable keys yields an empty map`() {
        // the caller then defaults every field to 0 — no crash, no bogus quota
        check(SubscriptionFetcher.parseSubscriptionUserinfo("").isEmpty())
        check(SubscriptionFetcher.parseSubscriptionUserinfo("plan=pro").isEmpty())
        check(SubscriptionFetcher.parseSubscriptionUserinfo("garbage").isEmpty())
    }

    @Test
    fun `userinfo accepts a total without expire and vice versa`() {
        val noExpire = SubscriptionFetcher.parseSubscriptionUserinfo("upload=1; download=2; total=100")
        check(noExpire["total"] == 100L)
        check(noExpire["expire"] == null)

        val noTotal = SubscriptionFetcher.parseSubscriptionUserinfo("upload=1; download=2; expire=99")
        check(noTotal["total"] == null)
        check(noTotal["expire"] == 99L)
    }
}
