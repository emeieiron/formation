package xyz.mcxross.formation.design.icons

object Icons {
  val ArrowLeft by lazy {
    icon("ArrowLeft") {
      stroke {
        line(19f, 12f, 5f, 12f)
        polyline(11f, 6f, 5f, 12f, 11f, 18f)
      }
    }
  }
  val ArrowRight by lazy {
    icon("ArrowRight") {
      stroke {
        line(5f, 12f, 19f, 12f)
        polyline(13f, 6f, 19f, 12f, 13f, 18f)
      }
    }
  }
  val ArrowUpRight by lazy {
    icon("ArrowUpRight") {
      stroke {
        line(7f, 17f, 17f, 7f)
        polyline(8f, 7f, 17f, 7f, 17f, 16f)
      }
    }
  }
  val ChevronRight by lazy {
    icon("ChevronRight") { stroke { polyline(9f, 5.5f, 15.5f, 12f, 9f, 18.5f) } }
  }
  val ChevronLeft by lazy {
    icon("ChevronLeft") { stroke { polyline(15f, 5.5f, 8.5f, 12f, 15f, 18.5f) } }
  }
  val ChevronDown by lazy {
    icon("ChevronDown") { stroke { polyline(5.5f, 9f, 12f, 15.5f, 18.5f, 9f) } }
  }
  val Close by lazy {
    icon("Close") {
      stroke {
        line(6f, 6f, 18f, 18f)
        line(18f, 6f, 6f, 18f)
      }
    }
  }
  val Check by lazy {
    icon("Check") { stroke(2.2f) { polyline(4.5f, 12.5f, 9.5f, 17.5f, 19.5f, 7f) } }
  }
  val Plus by lazy {
    icon("Plus") {
      stroke {
        line(12f, 5f, 12f, 19f)
        line(5f, 12f, 19f, 12f)
      }
    }
  }
  val More by lazy {
    icon("More") {
      fill {
        circle(5.5f, 12f, 1.7f)
        circle(12f, 12f, 1.7f)
        circle(18.5f, 12f, 1.7f)
      }
    }
  }
  val Refresh by lazy {
    icon("Refresh") {
      stroke {
        arc(12f, 12f, 7.5f, -60f, 300f)
        arrowHead(12f + 7.5f * cosDeg(240f), 12f + 7.5f * sinDeg(240f), 330f)
      }
    }
  }
  val Share by lazy {
    icon("Share") {
      stroke {
        line(12f, 3.5f, 12f, 14.5f)
        polyline(8f, 7.5f, 12f, 3.5f, 16f, 7.5f)
        polyline(8.5f, 10.5f, 6.5f, 10.5f, 6.5f, 20.5f, 17.5f, 20.5f, 17.5f, 10.5f, 15.5f, 10.5f)
      }
    }
  }
  val Copy by lazy {
    icon("Copy") {
      stroke {
        roundRect(8f, 8f, 20f, 20f, 2.5f)
        moveTo(16f, 8f)
        lineTo(16f, 5.5f)
        curveTo(16f, 4.7f, 15.3f, 4f, 14.5f, 4f)
        lineTo(5.5f, 4f)
        curveTo(4.7f, 4f, 4f, 4.7f, 4f, 5.5f)
        lineTo(4f, 14.5f)
        curveTo(4f, 15.3f, 4.7f, 16f, 5.5f, 16f)
        lineTo(8f, 16f)
      }
    }
  }
  val Sliders by lazy {
    icon("Sliders") {
      stroke {
        line(4f, 7f, 12.6f, 7f)
        line(17.4f, 7f, 20f, 7f)
        circle(15f, 7f, 2.4f)
        line(4f, 17f, 6.6f, 17f)
        line(11.4f, 17f, 20f, 17f)
        circle(9f, 17f, 2.4f)
      }
    }
  }
  val Info by lazy {
    icon("Info") {
      stroke {
        circle(12f, 12f, 9f)
        line(12f, 11f, 12f, 16.5f)
      }
      fill { circle(12f, 7.8f, 1.15f) }
    }
  }
  val Alert by lazy {
    icon("Alert") {
      stroke {
        polygon(12f, 4f, 21f, 19.5f, 3f, 19.5f)
        line(12f, 9.8f, 12f, 13.8f)
      }
      fill { circle(12f, 16.6f, 1.1f) }
    }
  }
  val Clock by lazy {
    icon("Clock") {
      stroke {
        circle(12f, 12f, 9f)
        polyline(12f, 7f, 12f, 12f, 15.5f, 14f)
      }
    }
  }
  val LogOut by lazy {
    icon("LogOut") {
      stroke {
        moveTo(10f, 20f)
        lineTo(6.5f, 20f)
        curveTo(5.4f, 20f, 4.5f, 19.1f, 4.5f, 18f)
        lineTo(4.5f, 6f)
        curveTo(4.5f, 4.9f, 5.4f, 4f, 6.5f, 4f)
        lineTo(10f, 4f)
        line(10f, 12f, 20f, 12f)
        polyline(16f, 8f, 20f, 12f, 16f, 16f)
      }
    }
  }
  val Keypad by lazy {
    icon("Keypad") {
      fill {
        for (r in 0..2) for (c in 0..2) circle(6f + c * 6f, 6f + r * 6f, 1.6f)
      }
    }
  }

  val Qr by lazy {
    icon("Qr") {
      stroke {
        roundRect(3.5f, 3.5f, 10f, 10f, 1.6f)
        roundRect(14f, 3.5f, 20.5f, 10f, 1.6f)
        roundRect(3.5f, 14f, 10f, 20.5f, 1.6f)
      }
      fill {
        roundRect(5.9f, 5.9f, 7.6f, 7.6f, 0.4f)
        roundRect(16.4f, 5.9f, 18.1f, 7.6f, 0.4f)
        roundRect(5.9f, 16.4f, 7.6f, 18.1f, 0.4f)
        roundRect(14f, 14f, 16.6f, 16.6f, 0.6f)
        roundRect(17.9f, 14f, 20.5f, 16.6f, 0.6f)
        roundRect(14f, 17.9f, 16.6f, 20.5f, 0.6f)
        roundRect(17.9f, 17.9f, 20.5f, 20.5f, 0.6f)
      }
    }
  }
  val Scan by lazy {
    icon("Scan") {
      stroke {
        polyline(4f, 8.5f, 4f, 6.5f)
        arcTo(2.5f, 2.5f, 0f, false, true, 6.5f, 4f)
        lineTo(8.5f, 4f)
        polyline(15.5f, 4f, 17.5f, 4f)
        arcTo(2.5f, 2.5f, 0f, false, true, 20f, 6.5f)
        lineTo(20f, 8.5f)
        polyline(20f, 15.5f, 20f, 17.5f)
        arcTo(2.5f, 2.5f, 0f, false, true, 17.5f, 20f)
        lineTo(15.5f, 20f)
        polyline(8.5f, 20f, 6.5f, 20f)
        arcTo(2.5f, 2.5f, 0f, false, true, 4f, 17.5f)
        lineTo(4f, 15.5f)
        line(7.5f, 12f, 16.5f, 12f)
      }
    }
  }
  val Wifi by lazy {
    icon("Wifi") {
      stroke {
        arc(12f, 19f, 13f, 225f, 90f)
        arc(12f, 19f, 8.5f, 225f, 90f)
        arc(12f, 19f, 4.2f, 225f, 90f)
      }
      fill { circle(12f, 19f, 1.4f) }
    }
  }
  val Broadcast by lazy {
    icon("Broadcast") {
      fill { circle(12f, 12f, 2.1f) }
      stroke {
        arc(12f, 12f, 5.5f, -40f, 80f)
        arc(12f, 12f, 9f, -40f, 80f)
        arc(12f, 12f, 5.5f, 140f, 80f)
        arc(12f, 12f, 9f, 140f, 80f)
      }
    }
  }
  val Signal by lazy {
    icon("Signal") {
      stroke(2.3f) {
        line(5.5f, 18.5f, 5.5f, 15.5f)
        line(10f, 18.5f, 10f, 12.5f)
        line(14.5f, 18.5f, 14.5f, 9f)
        line(19f, 18.5f, 19f, 5.5f)
      }
    }
  }

  val User by lazy {
    icon("User") {
      stroke {
        circle(12f, 8.5f, 3.8f)
        moveTo(5f, 20f)
        curveTo(5f, 16.4f, 8.1f, 14f, 12f, 14f)
        curveTo(15.9f, 14f, 19f, 16.4f, 19f, 20f)
      }
    }
  }
  val Users by lazy {
    icon("Users") {
      stroke {
        circle(9f, 8.5f, 3.3f)
        moveTo(3f, 19.5f)
        curveTo(3f, 16.3f, 5.7f, 14.2f, 9f, 14.2f)
        curveTo(12.3f, 14.2f, 15f, 16.3f, 15f, 19.5f)
        arc(16.5f, 9f, 2.7f, -120f, 250f)
        moveTo(17.2f, 14.3f)
        curveTo(19.6f, 14.6f, 21f, 16.4f, 21f, 18.8f)
      }
    }
  }
  val Phone by lazy {
    icon("Phone") {
      stroke {
        roundRect(7f, 2.8f, 17f, 21.2f, 2.4f)
        line(10.8f, 18.4f, 13.2f, 18.4f)
      }
    }
  }
  val Seeker by lazy {
    icon("Seeker") {
      stroke { roundRect(6.5f, 2.8f, 17.5f, 21.2f, 2.6f) }
      fill { star(12f, 12f, 4.6f, 1.25f) }
    }
  }
  val Crown by lazy {
    icon("Crown") {
      stroke {
        polygon(4.5f, 17f, 3.5f, 8f, 8.5f, 11.5f, 12f, 5.5f, 15.5f, 11.5f, 20.5f, 8f, 19.5f, 17f)
        line(5f, 20.2f, 19f, 20.2f)
      }
    }
  }

  val Lock by lazy {
    icon("Lock") {
      stroke {
        roundRect(5f, 10.5f, 19f, 20.5f, 2.5f)
        moveTo(8f, 10.5f)
        lineTo(8f, 8f)
        arcTo(4f, 4f, 0f, false, true, 16f, 8f)
        lineTo(16f, 10.5f)
      }
      fill { circle(12f, 15.5f, 1.3f) }
    }
  }
  val Unlock by lazy {
    icon("Unlock") {
      stroke {
        roundRect(5f, 10.5f, 19f, 20.5f, 2.5f)
        moveTo(8f, 10.5f)
        lineTo(8f, 7f)
        arcTo(4f, 4f, 0f, false, true, 16f, 7f)
      }
      fill { circle(12f, 15.5f, 1.3f) }
    }
  }
  val Coin by lazy {
    icon("Coin") {
      stroke { circle(12f, 12f, 8.6f) }
      fill { star(12f, 12f, 4.4f, 1.2f) }
    }
  }
  val Wallet by lazy {
    icon("Wallet") {
      stroke {
        roundRect(3.5f, 6f, 20.5f, 19.5f, 2.6f)
        roundRect(14.5f, 10.5f, 20.5f, 15f, 1.6f)
        polyline(6f, 6f, 15.5f, 3.5f, 16.6f, 6f)
      }
      fill { circle(17.2f, 12.75f, 1f) }
    }
  }
  val Trophy by lazy {
    icon("Trophy") {
      stroke {
        moveTo(7.5f, 4.5f)
        lineTo(16.5f, 4.5f)
        lineTo(16.5f, 9.5f)
        arcTo(4.5f, 4.5f, 0f, false, true, 7.5f, 9.5f)
        close()
        arc(7.5f, 7.5f, 2.4f, 90f, 180f)
        arc(16.5f, 7.5f, 2.4f, -90f, 180f)
        line(12f, 14f, 12f, 18f)
        line(8.5f, 19.5f, 15.5f, 19.5f)
      }
    }
  }
  val Spark by lazy { icon("Spark") { fill { star(12f, 12f, 9.2f, 2.4f) } } }
  val Sparkles by lazy {
    icon("Sparkles") {
      fill {
        star(10f, 13.5f, 7.4f, 1.9f)
        star(18.6f, 5.4f, 3.4f, 0.95f)
      }
    }
  }
  val Bolt by lazy {
    icon("Bolt") {
      fill { polygon(13.6f, 2.5f, 5.5f, 13.6f, 11f, 13.6f, 10f, 21.5f, 18.5f, 10.2f, 12.8f, 10.2f) }
    }
  }
  val Flame by lazy {
    icon("Flame") {
      fill {
        moveTo(12f, 2.5f)
        curveTo(13.4f, 6f, 17.8f, 8.4f, 17.8f, 13.8f)
        curveTo(17.8f, 17.6f, 15.2f, 20.6f, 12f, 20.6f)
        curveTo(8.8f, 20.6f, 6.2f, 17.8f, 6.2f, 14.2f)
        curveTo(6.2f, 11.2f, 8.2f, 9.2f, 9.3f, 7.4f)
        curveTo(9.8f, 10.2f, 10.9f, 11.2f, 12f, 11.6f)
        curveTo(12.9f, 9.2f, 13.1f, 6.1f, 12f, 2.5f)
        close()
      }
    }
  }

  val Tap by lazy {
    icon("Tap") {
      fill { circle(12f, 14.5f, 2.6f) }
      stroke {
        arc(12f, 14.5f, 6.2f, 205f, 130f)
        arc(12f, 14.5f, 10f, 215f, 110f)
      }
    }
  }
  val Hold by lazy {
    icon("Hold") {
      fill { circle(12f, 12f, 3.4f) }
      stroke(alpha = 0.35f) { circle(12f, 12f, 8.2f) }
      stroke { arc(12f, 12f, 8.2f, -90f, 250f) }
    }
  }
  val Shake by lazy {
    icon("Shake") {
      stroke {
        phone(12f, 12f, 7f, 12.5f, -14f)
        arc(12f, 12f, 9.6f, 150f, 50f)
        arc(12f, 12f, 9.6f, -20f, 50f)
      }
    }
  }
  val Rotate by lazy {
    icon("Rotate") {
      stroke {
        roundRect(8.5f, 8f, 15.5f, 20f, 1.8f)
        arc(12f, 12f, 9.4f, 205f, 130f)
        arrowHead(12f + 9.4f * cosDeg(335f), 12f + 9.4f * sinDeg(335f), 65f, 3f)
      }
    }
  }
  val Flip by lazy {
    icon("Flip") {
      stroke {
        roundRect(4.5f, 4.5f, 12f, 19.5f, 2f)
        arc(16.5f, 12f, 4f, 90f, -180f)
        arrowHead(16.5f, 8f, 180f, 2.8f)
      }
    }
  }
  val Swing by lazy {
    icon("Swing") {
      stroke {
        arc(4f, 20f, 14f, -80f, 70f)
        arrowHead(4f + 14f * cosDeg(-10f), 20f + 14f * sinDeg(-10f), 80f)
        arc(4f, 20f, 9f, -70f, 45f)
      }
    }
  }
  val Cover by lazy {
    icon("Cover") {
      stroke {
        roundRect(7.5f, 9.5f, 16.5f, 22f, 2f)
        arc(12f, 10f, 7.5f, 200f, 140f)
        line(4.5f, 12.5f, 5.2f, 11.5f)
        line(19.5f, 12.5f, 18.8f, 11.5f)
      }
    }
  }
  val Tilt by lazy {
    icon("Tilt") {
      stroke {
        phone(12f, 10.5f, 7f, 12f, 20f)
        arc(12f, 11f, 9.5f, 55f, 70f)
        arrowHead(12f + 9.5f * cosDeg(125f), 11f + 9.5f * sinDeg(125f), 215f, 2.8f)
        arrowHead(12f + 9.5f * cosDeg(55f), 11f + 9.5f * sinDeg(55f), -35f, 2.8f)
      }
    }
  }
  val Target by lazy {
    icon("Target") {
      stroke {
        circle(12f, 12f, 9f)
        circle(12f, 12f, 5.4f)
      }
      fill { circle(12f, 12f, 2f) }
    }
  }
  val Pulse by lazy {
    icon("Pulse") {
      stroke { polyline(3f, 12f, 7f, 12f, 9.5f, 6f, 13.5f, 18f, 16f, 12f, 21f, 12f) }
    }
  }

  val Rally by lazy {
    icon("Rally") {
      stroke {
        moveTo(4f, 19.5f)
        quadTo(8.5f, 3.5f, 17.5f, 9.5f)
      }
      stroke(alpha = 0.55f) { line(3f, 21.2f, 8.5f, 21.2f) }
      fill { circle(18.6f, 10.4f, 2.7f) }
    }
  }
  val Circuit by lazy {
    icon("Circuit") {
      stroke {
        circle(6f, 6f, 2.6f)
        circle(18f, 6f, 2.6f)
        circle(18f, 18f, 2.6f)
        circle(6f, 18f, 2.6f)
        line(8.6f, 6f, 15.4f, 6f)
        line(18f, 8.6f, 18f, 15.4f)
        line(15.4f, 18f, 8.6f, 18f)
        line(6f, 15.4f, 6f, 8.6f)
      }
      fill { circle(6f, 6f, 1.3f) }
    }
  }
  val Sync by lazy {
    icon("Sync") {
      stroke {
        circle(12f, 12f, 9f)
        circle(12f, 12f, 5f)
      }
      fill { circle(12f, 12f, 1.8f) }
    }
  }
  val Formation by lazy {
    icon("Formation") {
      stroke { polyline(4f, 17f, 8f, 12f, 12f, 6f, 16f, 12f, 20f, 17f) }
      fill {
        circle(4f, 17f, 1.9f)
        circle(8f, 12f, 1.9f)
        circle(12f, 6f, 2.4f)
        circle(16f, 12f, 1.9f)
        circle(20f, 17f, 1.9f)
      }
    }
  }
  val Rush by lazy {
    icon("Rush") {
      stroke {
        arc(12f, 15f, 8.5f, 180f, 180f)
        line(12f, 15f, 16.4f, 9.6f)
        line(3.5f, 15f, 5f, 15f)
        line(20.5f, 15f, 19f, 15f)
        line(12f, 6.5f, 12f, 8f)
      }
      fill { circle(12f, 15f, 1.8f) }
    }
  }
}
