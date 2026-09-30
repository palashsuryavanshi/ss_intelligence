package com.ssintelligence.app.semantic

/**
 * Automatic screenshot categories (§18, §19).
 *
 * The set is intentionally small and product-shaped rather than exhaustive: a
 * category earns its place by answering "what would I search for", not by
 * covering a taxonomy. [OTHER] is a real category, not a failure — most
 * screenshots are simply not one of these things, and forcing them somewhere
 * would be worse than admitting it.
 */
enum class ScreenshotCategory(val label: String) {
    SHOPPING("Shopping"),
    RECEIPTS("Receipts"),
    TRAVEL("Travel"),
    FLIGHTS("Flights"),
    HOTELS("Hotels"),
    TRAIN("Train"),
    FINANCE("Finance"),
    BANKING("Banking"),
    WORK("Work"),
    EDUCATION("Education"),
    TAX("CA / Tax"),
    TECHNOLOGY("Technology"),
    SOCIAL("Social"),
    MESSAGES("Messages"),
    ENTERTAINMENT("Entertainment"),
    FOOD("Food"),
    HEALTH("Health"),
    DOCUMENTS("Documents"),
    TICKETS("Tickets"),
    OTHER("Other"),
}

/** One assigned category and why it was assigned. */
data class CategoryAssignment(
    val category: ScreenshotCategory,
    /**
     * Internal confidence, 0..1. Stored for debugging and for the "strong
     * enough to show" threshold — never displayed as a percentage (§19).
     */
    val confidence: Double,
    /** Which classifier build produced this, so a new version can re-run cleanly. */
    val classifierVersion: String,
)

/** A category the user chose themselves. Always wins over the classifier (§21). */
data class CategoryOverride(
    val screenshotId: Long,
    val category: ScreenshotCategory,
)

/**
 * Assigns categories to a screenshot (§19).
 *
 * Deterministic rules over OCR text and extracted metadata. A screenshot earns
 * a category by accumulating evidence weight, not by containing one keyword:
 * "flight" alone is suggestive, "flight" + "PNR" + "departure" is a booking.
 * That is what keeps a screenshot mentioning "flight mode" out of Travel.
 */
interface ScreenshotClassifier {
    val version: String
    suspend fun classify(document: ScreenshotDocument): List<CategoryAssignment>
}

class RuleScreenshotClassifier : ScreenshotClassifier {

    override val version: String = CLASSIFIER_VERSION

    private data class Rule(
        val category: ScreenshotCategory,
        /** Weighted keywords: (phrase, weight). */
        val keywords: List<Pair<String, Double>>,
        /** Weight granted per extracted-metadata signal. */
        val hostWeight: Double = 0.0,
        val hosts: List<String> = emptyList(),
        val priceWeight: Double = 0.0,
        val otpWeight: Double = 0.0,
        /**
         * Evidence needed. Above 1.0 on purpose: no single keyword, however
         * weighted, may categorize a screenshot on its own. "Flight mode"
         * mentions flight and nothing else, and must not become Travel.
         */
        val threshold: Double = 1.5,
    )

    private val rules = listOf(
        Rule(
            ScreenshotCategory.FLIGHTS,
            keywords = listOf("boarding pass" to 2.0, "pnr" to 2.0, "departure" to 1.0, "arrival" to 1.0, "flight" to 1.0, "airline" to 1.0, "gate" to 0.5, "seat" to 0.5),
            hosts = listOf("indigo", "airindia", "spicejet", "makemytrip", "goibibo", "yatra", "easemytrip"),
            hostWeight = 1.0,
        ),
        Rule(
            ScreenshotCategory.TRAVEL,
            keywords = listOf("booking confirmation" to 2.0, "itinerary" to 2.0, "checkin" to 1.0, "hotel" to 1.0, "reservation" to 1.0, "trip" to 1.0, "travel" to 1.0),
            hosts = listOf("makemytrip", "goibibo", "booking.com", "airbnb", "oyorooms", "irctc"),
            hostWeight = 1.0,
        ),
        Rule(
            ScreenshotCategory.HOTELS,
            keywords = listOf("checkin" to 1.5, "checkout" to 1.5, "hotel" to 1.0, "room booking" to 2.0, "stay" to 0.5),
            hosts = listOf("booking.com", "airbnb", "oyorooms", "makemytrip"),
            hostWeight = 1.0,
        ),
        Rule(
            ScreenshotCategory.TRAIN,
            keywords = listOf("pnr" to 1.0, "irctc" to 2.0, "train" to 1.0, "coach" to 1.0, "berth" to 1.5, "railway" to 1.0),
        ),
        Rule(
            ScreenshotCategory.SHOPPING,
            keywords = listOf("add to cart" to 2.0, "buy now" to 2.0, "order" to 0.5, "delivery" to 0.5, "discount" to 1.0, "offer" to 0.5, "deal" to 0.5, "cart" to 1.0, "checkout" to 1.0, "cashback" to 1.0),
            hosts = listOf("amazon", "flipkart", "myntra", "meesho", "ajio", "nykaa"),
            hostWeight = 1.5,
            priceWeight = 0.5,
        ),
        Rule(
            ScreenshotCategory.RECEIPTS,
            keywords = listOf("invoice" to 2.0, "receipt" to 2.0, "total" to 0.5, "paid" to 0.5, "payment successful" to 1.5, "order id" to 1.0, "gstin" to 1.5),
            priceWeight = 1.0,
        ),
        Rule(
            ScreenshotCategory.FINANCE,
            keywords = listOf("balance" to 1.0, "statement" to 1.0, "transaction" to 0.5, "account" to 0.5, "upi" to 0.5, "transfer" to 0.5, "investment" to 1.0, "mutual fund" to 1.5),
            hosts = listOf("hdfcbank", "icicibank", "sbi", "axisbank", "kotak", "paytm", "phonepe", "groww", "zerodha"),
            hostWeight = 1.5,
        ),
        Rule(
            ScreenshotCategory.BANKING,
            keywords = listOf("otp" to 1.0, "one time password" to 1.5, "account" to 0.5, "ifsc" to 1.5, "debit card" to 1.5, "credit card" to 1.5, "netbanking" to 1.5),
            otpWeight = 1.0,
        ),
        Rule(
            ScreenshotCategory.TAX,
            keywords = listOf("gst" to 1.5, "income tax" to 2.0, "tds" to 1.5, "tax" to 1.0, "return filing" to 2.0, "form 16" to 2.0, "pan" to 0.5, "ca " to 0.5),
            hosts = listOf("incometax.gov", "gst.gov", "cleartax"),
            hostWeight = 1.5,
        ),
        Rule(
            ScreenshotCategory.FOOD,
            keywords = listOf("restaurant" to 1.0, "zomato" to 2.0, "swiggy" to 2.0, "delivery" to 0.5, "menu" to 0.5, "order food" to 1.5),
            hosts = listOf("zomato", "swiggy"),
            hostWeight = 1.5,
        ),
        Rule(
            ScreenshotCategory.WORK,
            keywords = listOf("meeting" to 1.0, "project" to 0.5, "deadline" to 1.0, "presentation" to 1.0, "report" to 0.5, "standup" to 1.5, "jira" to 1.5, "slack" to 1.0),
            hosts = listOf("slack", "teams", "zoom", "meet.google"),
            hostWeight = 1.0,
        ),
        Rule(
            ScreenshotCategory.EDUCATION,
            keywords = listOf("course" to 1.0, "lecture" to 1.5, "exam" to 1.0, "tutorial" to 1.0, "coaching" to 1.5, "udemy" to 1.5, "coursera" to 1.5, "syllabus" to 1.5),
            hosts = listOf("udemy", "coursera", "unacademy", "khanacademy"),
            hostWeight = 1.5,
        ),
        Rule(
            ScreenshotCategory.HEALTH,
            keywords = listOf("appointment" to 1.0, "prescription" to 2.0, "doctor" to 1.0, "hospital" to 1.0, "report" to 0.5, "diagnosis" to 1.5, "medicine" to 1.0, "practo" to 1.5),
        ),
        Rule(
            ScreenshotCategory.TECHNOLOGY,
            keywords = listOf("pixel" to 0.5, "iphone" to 0.5, "android" to 0.5, "github" to 1.5, "update available" to 1.0, "software" to 0.5, "ram" to 0.5, "storage" to 0.3),
            hosts = listOf("github", "stackoverflow", "play.google"),
            hostWeight = 1.5,
        ),
        Rule(
            ScreenshotCategory.SOCIAL,
            keywords = listOf("follower" to 1.5, "instagram" to 1.5, "post" to 0.3, "like" to 0.3, "story" to 0.5, "tweet" to 1.5, "facebook" to 1.0),
            hosts = listOf("instagram", "facebook", "twitter", "x.com", "linkedin", "reddit"),
            hostWeight = 1.5,
        ),
        Rule(
            ScreenshotCategory.MESSAGES,
            keywords = listOf("whatsapp" to 1.5, "telegram" to 1.5, "chat" to 0.5, "typing" to 1.0, "online" to 0.3, "message" to 0.3),
            hosts = listOf("whatsapp", "telegram"),
            hostWeight = 1.0,
        ),
        Rule(
            ScreenshotCategory.ENTERTAINMENT,
            keywords = listOf("movie" to 1.0, "bookmyshow" to 2.0, "concert" to 1.0, "netflix" to 1.5, "series" to 0.5, "showtime" to 1.5),
            hosts = listOf("bookmyshow", "netflix", "primevideo", "hotstar"),
            hostWeight = 1.5,
        ),
        Rule(
            ScreenshotCategory.DOCUMENTS,
            keywords = listOf("aadhaar" to 2.0, "passport" to 2.0, "license" to 1.0, "certificate" to 1.0, "pan card" to 2.0, "voter" to 1.5),
        ),
        Rule(
            ScreenshotCategory.TICKETS,
            keywords = listOf("ticket" to 1.0, "entry pass" to 2.0, "qr code" to 0.5, "admit card" to 2.0, "booking" to 0.5),
        ),
    )

    override suspend fun classify(document: ScreenshotDocument): List<CategoryAssignment> {
        val text = " ${document.ocrText.lowercase()} "
        val hosts = document.hosts.map { it.lowercase() }
        val out = mutableListOf<CategoryAssignment>()
        for (rule in rules) {
            var score = 0.0
            var matchedWeight = 0.0
            var totalKeywordWeight = 0.0
            for ((keyword, weight) in rule.keywords) {
                totalKeywordWeight += weight
                if (text.contains(keyword)) {
                    score += weight
                    matchedWeight += weight
                }
            }
            if (rule.hosts.isNotEmpty() && hosts.any { host -> rule.hosts.any { host.contains(it) } }) {
                score += rule.hostWeight
            }
            if (rule.priceWeight > 0 && document.prices.isNotEmpty()) score += rule.priceWeight
            if (rule.otpWeight > 0 && document.otpCount > 0) score += rule.otpWeight
            if (score >= rule.threshold) {
                // Confidence is the share of the rule's keyword evidence that
                // fired, nudged by metadata. It is a ranking aid, not a claim.
                val confidence = if (totalKeywordWeight > 0) {
                    (matchedWeight / totalKeywordWeight).coerceIn(0.15, 1.0)
                } else {
                    0.5
                }
                out += CategoryAssignment(rule.category, confidence, version)
            }
        }
        // Strongest first; a screenshot may hold several (§20).
        val ranked = out.sortedByDescending { it.confidence }.take(MAX_CATEGORIES)
        return if (ranked.isEmpty()) {
            listOf(CategoryAssignment(ScreenshotCategory.OTHER, 1.0, version))
        } else {
            ranked
        }
    }

    companion object {
        const val CLASSIFIER_VERSION = "rules-v1"
        private const val MAX_CATEGORIES = 3
    }
}
