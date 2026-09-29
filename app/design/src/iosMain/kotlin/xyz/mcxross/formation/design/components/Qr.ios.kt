package xyz.mcxross.formation.design.components

// Phones only show a QR code when hosting, and hosting needs a Seeker, so iOS never draws one.
actual fun encodeQr(text: String): QrMatrix? = null
