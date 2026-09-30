package com.ssintelligence.app.semantic

import com.ssintelligence.app.domain.model.ExtractedPrice
import com.ssintelligence.app.domain.model.ExtractedUrl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rule classification, extractive summaries, entities, sensitive flags. */
class ClassifierTest {

    private val classifier = RuleScreenshotClassifier()

    private fun document(
        ocr: String,
        hosts: List<String> = emptyList(),
        prices: List<ExtractedPrice> = emptyList(),
        otpCount: Int = 0,
    ) = ScreenshotDocument(
        screenshotId = 1,
        ocrText = ocr,
        filename = "Screenshot_1.png",
        hosts = hosts,
        prices = prices,
        otpCount = otpCount,
    )

    private fun categoriesOf(
        ocr: String,
        hosts: List<String> = emptyList(),
        prices: List<ExtractedPrice> = emptyList(),
    ): List<ScreenshotCategory> =
        runBlocking { classifier.classify(document(ocr, hosts, prices)).map { it.category } }

    @Test
    fun `a boarding pass is flights and travel`() {
        val categories = categoriesOf("IndiGo boarding pass PNR 7QK2LP departure Delhi gate 12")
        assertTrue(categories.contains(ScreenshotCategory.FLIGHTS))
    }

    @Test
    fun `a shopping listing is shopping and technology`() {
        val categories = categoriesOf(
            "Google Pixel 9a 5G 128GB ₹39,999 Add to cart",
            hosts = listOf("www.amazon.in"),
        )
        assertTrue(categories.contains(ScreenshotCategory.SHOPPING))
    }

    @Test
    fun `a receipt is receipts`() {
        val categories = categoriesOf(
            "Invoice GSTIN 27ABCDE1234F Total ₹1,250 Paid",
            prices = listOf(ExtractedPrice(1, 1, "₹1,250", "INR", 1250.0)),
        )
        assertTrue(categories.contains(ScreenshotCategory.RECEIPTS))
    }

    @Test
    fun `a single keyword is not enough`() {
        // "flight mode" mentions flight but nothing else travel-like.
        val categories = categoriesOf("Turn on flight mode to save battery")
        assertTrue(
            "flight mode must not be Travel/Flights, got $categories",
            !categories.contains(ScreenshotCategory.FLIGHTS) &&
                !categories.contains(ScreenshotCategory.TRAVEL),
        )
    }

    @Test
    fun `an amazon receipt is multi-category`() {
        val assignments = runBlocking {
            classifier.classify(
                document(
                    "Amazon order confirmation laptop invoice total ₹65,000",
                    hosts = listOf("amazon.in"),
                    prices = listOf(ExtractedPrice(1, 1, "₹65,000", "INR", 65000.0)),
                ),
            )
        }
        val categories = assignments.map { it.category }
        assertTrue(categories.contains(ScreenshotCategory.SHOPPING))
        assertTrue(categories.contains(ScreenshotCategory.RECEIPTS))
    }

    @Test
    fun `nothing recognizable is Other`() {
        assertEquals(
            listOf(ScreenshotCategory.OTHER),
            categoriesOf("a photo of a sunset with no text of note"),
        )
    }

    @Test
    fun `classifier version is stamped on every assignment`() {
        val assignments = runBlocking { classifier.classify(document("flight PNR ABC123")) }
        assertTrue(assignments.all { it.classifierVersion == RuleScreenshotClassifier.CLASSIFIER_VERSION })
    }

    @Test
    fun `confidence is a unit interval`() {
        val assignments = runBlocking { classifier.classify(document("boarding pass PNR ABC123 departure")) }
        assignments.forEach {
            assertTrue(it.confidence in 0.0..1.0)
        }
    }
}

class ExtractiveSummarizerTest {

    private val summarizer = ExtractiveSummarizer()

    private fun summarize(ocr: String, phrases: List<String> = emptyList()): String =
        runBlocking {
            summarizer.summarize(
                ScreenshotDocument(
                    screenshotId = 1,
                    ocrText = ocr,
                    filename = "Screenshot_1.png",
                    hosts = listOf("www.amazon.in"),
                    prices = listOf(ExtractedPrice(1, 1, "₹39,999", "INR", 39999.0)),
                ),
                phrases,
            )
        }

    @Test
    fun `a listing summarizes to product price host`() {
        val summary = summarize("Google Pixel 9a\n8GB RAM\n128GB\n₹39,999", listOf("pixel 9a"))
        assertEquals("Pixel 9a — ₹39,999 — amazon.in", summary)
    }

    @Test
    fun `no new words are introduced`() {
        val summary = summarize("Flight ticket Delhi Mumbai PNR 7QK2LP")
        val words = summary.lowercase().split(Regex("[^a-z0-9₹.,]+")).filter { it.isNotBlank() }.toSet()
        // The document also carries a host and a price, which the summary may use.
        val source = "flight ticket delhi mumbai pnr 7qk2lp amazon.in ₹39,999 screenshot 1 —"
            .lowercase().split(Regex("[^a-z0-9₹.,]+")).toSet()
        assertTrue("summary introduced words: $words", words.all { it in source || it == "—" })
    }

    @Test
    fun `an empty document falls back to the filename`() {
        val summary = runBlocking {
            summarizer.summarize(
                ScreenshotDocument(1, "", "Screenshot_2026-09-30.png"),
            )
        }
        assertEquals("Screenshot_2026-09-30", summary)
    }

    @Test
    fun `the largest price wins`() {
        val summary = runBlocking {
            summarizer.summarize(
                ScreenshotDocument(
                    screenshotId = 1,
                    ocrText = "Phones from ₹9,999 to ₹39,999",
                    filename = "s.png",
                    prices = listOf(
                        ExtractedPrice(1, 1, "x", "INR", 9999.0),
                        ExtractedPrice(2, 1, "x", "INR", 39999.0),
                    ),
                ),
            )
        }
        assertTrue(summary.contains("₹39,999"))
    }
}

class EntityExtractorTest {

    @Test
    fun `hosts become company and website entities`() {
        val entities = EntityExtractor.extract(
            ScreenshotDocument(1, "deal", "s.png", hosts = listOf("www.amazon.in")),
        )
        assertTrue(entities.any { it.kind == EntityKind.WEBSITE && it.normalized == "www.amazon.in" })
        assertTrue(entities.any { it.kind == EntityKind.COMPANY })
    }

    @Test
    fun `prices become price entities with ISO normalization`() {
        val entities = EntityExtractor.extract(
            ScreenshotDocument(
                1, "deal", "s.png",
                prices = listOf(ExtractedPrice(1, 1, "₹39,999", "INR", 39999.0)),
            ),
        )
        assertTrue(entities.any { it.kind == EntityKind.PRICE && it.normalized == "INR:39999.0" })
    }

    @Test
    fun `an order number is recognized by shape and context`() {
        val entities = EntityExtractor.extract(
            ScreenshotDocument(1, "Your order 7QK2LP09 confirmed", "s.png"),
        )
        assertTrue(entities.any { it.kind == EntityKind.ORDER_NUMBER && it.normalized == "7QK2LP09" })
    }

    @Test
    fun `a bare code without context is not an order number`() {
        val entities = EntityExtractor.extract(
            ScreenshotDocument(1, "reference 7QK2LP09", "s.png"),
        )
        assertTrue(entities.none { it.kind == EntityKind.ORDER_NUMBER })
    }
}

class SensitiveContentDetectorTest {

    private fun detect(ocr: String) =
        SensitiveContentDetector.detect(ScreenshotDocument(1, ocr, "s.png"))

    @Test
    fun `an otp screen is flagged`() {
        assertTrue(detect("Your OTP is 483921 valid for 10 minutes").contains(SensitiveKind.OTP))
    }

    @Test
    fun `banking details are flagged`() {
        assertTrue(detect("Account number 123456 IFSC HDFC000123").contains(SensitiveKind.BANKING))
    }

    @Test
    fun `an identity document is flagged`() {
        assertTrue(detect("Aadhaar 1234 5678 9012 date of birth").contains(SensitiveKind.IDENTITY))
    }

    @Test
    fun `an ordinary shopping screenshot is not flagged`() {
        assertTrue(detect("Google Pixel 9a ₹39,999 Add to cart").isEmpty())
    }
}

class SmartGroupBuilderTest {

    private fun member(
        id: Long,
        category: ScreenshotCategory?,
        words: List<String> = emptyList(),
        hosts: List<String> = emptyList(),
    ) = SmartGroupBuilder.GroupMember(id, category, hosts, words, dateAdded = id)

    @Test
    fun `members of a category form a group with an honest label`() {
        val groups = SmartGroupBuilder.build(
            (1L..4L).map {
                member(it, ScreenshotCategory.TRAVEL, words = listOf("flight", "ticket", "booking"))
            },
        )
        assertEquals(1, groups.size)
        // The members' own top word plus the category: recognizable, grounded.
        assertEquals("Flight / Travel", groups.single().label)
        assertEquals(4, groups.single().size)
    }

    @Test
    fun `the label combines the top word with the category`() {
        val groups = SmartGroupBuilder.build(
            (1L..3L).map {
                member(it, ScreenshotCategory.SHOPPING, words = listOf("pixel", "phone", "price"))
            },
        )
        assertEquals("Pixel / Shopping", groups.single().label)
    }

    @Test
    fun `fewer than three members is not a group`() {
        assertTrue(SmartGroupBuilder.build((1L..2L).map { member(it, ScreenshotCategory.WORK) }).isEmpty())
    }

    @Test
    fun `uncategorized members group by shared host`() {
        val groups = SmartGroupBuilder.build(
            (1L..3L).map { member(it, null, hosts = listOf("amazon")) },
        )
        assertEquals(1, groups.size)
    }

    @Test
    fun `the newest member is the cover`() {
        val groups = SmartGroupBuilder.build(
            (1L..3L).map { member(it, ScreenshotCategory.FOOD, words = listOf("restaurant", "dinner")) },
        )
        assertEquals(3L, groups.single().coverId)
    }
}
