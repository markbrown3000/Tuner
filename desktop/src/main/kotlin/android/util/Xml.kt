package android.util

/** Stand-in for Android's Xml helper, so the TV app's guide reader runs unchanged on a PC. */
object Xml {
    fun newPullParser(): org.xmlpull.v1.XmlPullParser = org.kxml2.io.KXmlParser()
}
