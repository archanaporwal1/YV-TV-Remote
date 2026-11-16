import SwiftUI

struct YvBrandLogoMark: View {
  var body: some View {
    GeometryReader { geo in
      let w = geo.size.width
      let h = geo.size.height
      let gold = LinearGradient(
        colors: [
          Color(red: 0.96, green: 0.80, blue: 0.36),
          Color(red: 0.82, green: 0.58, blue: 0.16),
        ],
        startPoint: .topLeading,
        endPoint: .bottomTrailing
      )
      ZStack {
        // Corner framing brackets
        Path { p in
          p.move(to: CGPoint(x: w * 0.12, y: h * 0.30))
          p.addLine(to: CGPoint(x: w * 0.12, y: h * 0.16))
          p.addLine(to: CGPoint(x: w * 0.28, y: h * 0.16))
          p.move(to: CGPoint(x: w * 0.72, y: h * 0.16))
          p.addLine(to: CGPoint(x: w * 0.88, y: h * 0.16))
          p.addLine(to: CGPoint(x: w * 0.88, y: h * 0.30))
        }
        .stroke(gold, style: StrokeStyle(lineWidth: w * 0.08, lineCap: .round, lineJoin: .round))

        Text("YV")
          .font(.system(size: w * 0.44, weight: .black, design: .rounded))
          .foregroundStyle(gold)
          .offset(y: h * 0.05)
      }
    }
  }
}

struct DPadModeGlyphView: View {
  let tint: Color

  var body: some View {
    GeometryReader { geo in
      let w = geo.size.width
      let h = geo.size.height
      let cx = w * 0.5
      let cy = h * 0.5
      ZStack {
        Circle()
          .stroke(tint, lineWidth: w * 0.12)
          .padding(w * 0.06)
        Circle()
          .fill(tint)
          .frame(width: w * 0.28, height: h * 0.28)
        Path { p in
          p.move(to: CGPoint(x: cx, y: h * 0.15))
          p.addLine(to: CGPoint(x: cx, y: h * 0.27))
          p.move(to: CGPoint(x: cx, y: h * 0.85))
          p.addLine(to: CGPoint(x: cx, y: h * 0.73))
          p.move(to: CGPoint(x: w * 0.15, y: cy))
          p.addLine(to: CGPoint(x: w * 0.27, y: cy))
          p.move(to: CGPoint(x: w * 0.85, y: cy))
          p.addLine(to: CGPoint(x: w * 0.73, y: cy))
        }
        .stroke(tint, style: StrokeStyle(lineWidth: w * 0.11, lineCap: .round))
      }
    }
  }
}

struct TvInputGlyphView: View {
  let tint: Color

  var body: some View {
    GeometryReader { geo in
      let w = geo.size.width
      let h = geo.size.height
      Path { p in
        // Landscape TV bezel open on left for arrow
        p.move(to: CGPoint(x: w * 0.30, y: h * 0.28))
        p.addLine(to: CGPoint(x: w * 0.30, y: h * 0.20))
        p.addLine(to: CGPoint(x: w * 0.88, y: h * 0.20))
        p.addLine(to: CGPoint(x: w * 0.88, y: h * 0.78))
        p.addLine(to: CGPoint(x: w * 0.30, y: h * 0.78))
        p.addLine(to: CGPoint(x: w * 0.30, y: h * 0.70))

        // Inward shaft + arrow tip
        p.move(to: CGPoint(x: w * 0.10, y: h * 0.49))
        p.addLine(to: CGPoint(x: w * 0.58, y: h * 0.49))
        p.move(to: CGPoint(x: w * 0.43, y: h * 0.35))
        p.addLine(to: CGPoint(x: w * 0.60, y: h * 0.49))
        p.addLine(to: CGPoint(x: w * 0.43, y: h * 0.63))
      }
      .stroke(tint, style: StrokeStyle(lineWidth: w * 0.11, lineCap: .round, lineJoin: .round))
    }
  }
}

struct OttAppIconTileView: View {
  let shortcut: TvAppShortcut

  var body: some View {
    ZStack {
      RoundedRectangle(cornerRadius: 11, style: .continuous)
        .fill(backgroundGradient)
        .overlay(
          RoundedRectangle(cornerRadius: 11, style: .continuous)
            .stroke(
              shortcut.id == "youtube" ? Color.white.opacity(0.65) : Color.white.opacity(0.18),
              lineWidth: 1
            )
        )
      brandMark
        .padding(5)
    }
  }

  private var backgroundGradient: LinearGradient {
    switch shortcut.id {
    case "youtube":
      return LinearGradient(colors: [.white, Color(white: 0.95)], startPoint: .topLeading, endPoint: .bottomTrailing)
    case "netflix":
      return LinearGradient(colors: [Color(white: 0.09), Color(white: 0.04)], startPoint: .top, endPoint: .bottom)
    case "prime_video":
      return LinearGradient(
        colors: [Color(red: 0.0, green: 0.12, blue: 0.25), Color(red: 0.0, green: 0.33, blue: 0.61)],
        startPoint: .topLeading,
        endPoint: .bottomTrailing
      )
    case "jiocinema":
      return LinearGradient(
        colors: [Color(red: 1.0, green: 0.0, blue: 0.50), Color(red: 0.55, green: 0.0, blue: 0.32)],
        startPoint: .topLeading,
        endPoint: .bottomTrailing
      )
    case "hotstar":
      return LinearGradient(
        colors: [Color(red: 0.03, green: 0.07, blue: 0.19), Color(red: 0.05, green: 0.27, blue: 0.52)],
        startPoint: .topLeading,
        endPoint: .bottomTrailing
      )
    case "sonyliv":
      return LinearGradient(
        colors: [Color(red: 0.11, green: 0.08, blue: 0.24), Color(red: 0.05, green: 0.04, blue: 0.13)],
        startPoint: .topLeading,
        endPoint: .bottomTrailing
      )
    case "zee5":
      return LinearGradient(
        colors: [Color(red: 0.08, green: 0.03, blue: 0.15), Color(red: 0.03, green: 0.01, blue: 0.07)],
        startPoint: .topLeading,
        endPoint: .bottomTrailing
      )
    case "sunnxt":
      return LinearGradient(
        colors: [Color(red: 1.0, green: 0.23, blue: 0.0), Color(red: 1.0, green: 0.60, blue: 0.0)],
        startPoint: .topLeading,
        endPoint: .bottomTrailing
      )
    default:
      return LinearGradient(
        colors: [Color(red: 0.90, green: 0.0, blue: 0.14), Color(red: 0.64, green: 0.0, blue: 0.10)],
        startPoint: .topLeading,
        endPoint: .bottomTrailing
      )
    }
  }

  @ViewBuilder
  private var brandMark: some View {
    GeometryReader { geo in
      let w = geo.size.width
      let h = geo.size.height
      switch shortcut.id {
      case "youtube":
        ZStack {
          RoundedRectangle(cornerRadius: w * 0.24, style: .continuous)
            .fill(Color(red: 1.0, green: 0.0, blue: 0.0))
            .frame(width: w * 0.92, height: h * 0.68)
          Path { p in
            p.move(to: CGPoint(x: w * 0.41, y: h * 0.33))
            p.addLine(to: CGPoint(x: w * 0.68, y: h * 0.50))
            p.addLine(to: CGPoint(x: w * 0.41, y: h * 0.67))
            p.closeSubpath()
          }
          .fill(Color.white)
        }
        .frame(width: w, height: h)

      case "netflix":
        ZStack {
          Path { p in
            p.addRect(CGRect(x: w * 0.22, y: h * 0.08, width: w * 0.18, height: h * 0.84))
            p.addRect(CGRect(x: w * 0.60, y: h * 0.08, width: w * 0.18, height: h * 0.84))
          }
          .fill(Color(red: 0.70, green: 0.03, blue: 0.06))
          Path { p in
            p.move(to: CGPoint(x: w * 0.22, y: h * 0.08))
            p.addLine(to: CGPoint(x: w * 0.40, y: h * 0.08))
            p.addLine(to: CGPoint(x: w * 0.78, y: h * 0.92))
            p.addLine(to: CGPoint(x: w * 0.60, y: h * 0.92))
            p.closeSubpath()
          }
          .fill(Color(red: 0.90, green: 0.04, blue: 0.08))
        }

      case "prime_video":
        ZStack {
          Path { p in
            p.move(to: CGPoint(x: w * 0.36, y: h * 0.16))
            p.addLine(to: CGPoint(x: w * 0.68, y: h * 0.36))
            p.addLine(to: CGPoint(x: w * 0.36, y: h * 0.56))
            p.closeSubpath()
          }
          .fill(Color.white)
          Path { p in
            p.move(to: CGPoint(x: w * 0.14, y: h * 0.66))
            p.addQuadCurve(
              to: CGPoint(x: w * 0.84, y: h * 0.64),
              control: CGPoint(x: w * 0.50, y: h * 0.94)
            )
            p.move(to: CGPoint(x: w * 0.72, y: h * 0.59))
            p.addLine(to: CGPoint(x: w * 0.87, y: h * 0.62))
            p.addLine(to: CGPoint(x: w * 0.82, y: h * 0.77))
          }
          .stroke(
            Color(red: 0.0, green: 0.66, blue: 0.88),
            style: StrokeStyle(lineWidth: w * 0.10, lineCap: .round, lineJoin: .round)
          )
        }

      case "jiocinema":
        ZStack {
          Circle()
            .fill(Color.white.opacity(0.22))
            .frame(width: w * 0.88, height: h * 0.88)
          Circle()
            .stroke(Color.white, lineWidth: w * 0.09)
            .frame(width: w * 0.82, height: h * 0.82)
          Path { p in
            p.move(to: CGPoint(x: w * 0.41, y: h * 0.31))
            p.addLine(to: CGPoint(x: w * 0.68, y: h * 0.50))
            p.addLine(to: CGPoint(x: w * 0.41, y: h * 0.69))
            p.closeSubpath()
          }
          .fill(Color.white)
          Circle()
            .fill(Color(red: 1.0, green: 0.84, blue: 0.31))
            .frame(width: w * 0.14, height: h * 0.14)
            .position(x: w * 0.78, y: h * 0.22)
        }

      case "hotstar":
        ZStack {
          Path { p in
            p.move(to: CGPoint(x: w * 0.14, y: h * 0.42))
            p.addQuadCurve(
              to: CGPoint(x: w * 0.86, y: h * 0.42),
              control: CGPoint(x: w * 0.50, y: h * 0.06)
            )
          }
          .stroke(
            LinearGradient(
              colors: [Color(red: 0.0, green: 0.90, blue: 1.0), .white, Color(red: 1.0, green: 0.84, blue: 0.31)],
              startPoint: .leading,
              endPoint: .trailing
            ),
            style: StrokeStyle(lineWidth: w * 0.09, lineCap: .round)
          )

          FourPointSparkleShape(center: CGPoint(x: w * 0.50, y: h * 0.60), outer: w * 0.36, inner: w * 0.13)
            .fill(
              RadialGradient(
                colors: [.white, Color(red: 0.38, green: 0.85, blue: 1.0)],
                center: UnitPoint(x: 0.5, y: 0.6),
                startRadius: 0,
                endRadius: w * 0.36
              )
            )
        }

      case "sonyliv":
        ZStack {
          Path { p in
            p.move(to: CGPoint(x: w * 0.16, y: h * 0.14))
            p.addLine(to: CGPoint(x: w * 0.84, y: h * 0.48))
            p.addLine(to: CGPoint(x: w * 0.16, y: h * 0.86))
            p.closeSubpath()
          }
          .fill(
            LinearGradient(
              colors: [Color.orange, Color(red: 1.0, green: 0.16, blue: 0.52), Color.purple],
              startPoint: .topLeading,
              endPoint: .bottomTrailing
            )
          )
          Path { p in
            p.move(to: CGPoint(x: w * 0.24, y: h * 0.24))
            p.addLine(to: CGPoint(x: w * 0.78, y: h * 0.50))
            p.addLine(to: CGPoint(x: w * 0.24, y: h * 0.78))
            p.closeSubpath()
          }
          .fill(
            LinearGradient(
              colors: [Color(red: 0.0, green: 0.83, blue: 1.0), Color.purple],
              startPoint: .topLeading,
              endPoint: .bottomTrailing
            )
          )
          Path { p in
            p.move(to: CGPoint(x: w * 0.34, y: h * 0.36))
            p.addLine(to: CGPoint(x: w * 0.60, y: h * 0.50))
            p.addLine(to: CGPoint(x: w * 0.34, y: h * 0.64))
            p.closeSubpath()
          }
          .fill(Color.white)
        }

      case "zee5":
        ZStack {
          Circle()
            .stroke(
              AngularGradient(
                colors: [
                  Color(red: 1.0, green: 0.0, blue: 0.50),
                  Color.purple,
                  Color(red: 0.0, green: 0.83, blue: 1.0),
                  Color(red: 1.0, green: 0.0, blue: 0.50),
                ],
                center: .center
              ),
              lineWidth: w * 0.10
            )
            .padding(w * 0.06)

          Path { p in
            // Z
            p.move(to: CGPoint(x: w * 0.23, y: h * 0.35))
            p.addLine(to: CGPoint(x: w * 0.47, y: h * 0.35))
            p.addLine(to: CGPoint(x: w * 0.23, y: h * 0.65))
            p.addLine(to: CGPoint(x: w * 0.47, y: h * 0.65))
            // 5
            p.move(to: CGPoint(x: w * 0.77, y: h * 0.35))
            p.addLine(to: CGPoint(x: w * 0.56, y: h * 0.35))
            p.addLine(to: CGPoint(x: w * 0.56, y: h * 0.49))
            p.addLine(to: CGPoint(x: w * 0.72, y: h * 0.49))
            p.addQuadCurve(
              to: CGPoint(x: w * 0.78, y: h * 0.58),
              control: CGPoint(x: w * 0.80, y: h * 0.50)
            )
            p.addQuadCurve(
              to: CGPoint(x: w * 0.56, y: h * 0.65),
              control: CGPoint(x: w * 0.77, y: h * 0.66)
            )
          }
          .stroke(Color.white, style: StrokeStyle(lineWidth: w * 0.09, lineCap: .round, lineJoin: .round))
        }

      case "sunnxt":
        ZStack {
          Circle()
            .fill(
              RadialGradient(
                colors: [Color(red: 1.0, green: 0.96, blue: 0.62), Color(red: 1.0, green: 0.84, blue: 0.31)],
                center: .center,
                startRadius: 0,
                endRadius: w * 0.24
              )
            )
            .frame(width: w * 0.48, height: h * 0.48)
          Path { p in
            let cx = w * 0.5
            let cy = h * 0.5
            for i in 0..<8 {
              let angle = Double(i) * (.pi / 4.0)
              let dx = CGFloat(cos(angle))
              let dy = CGFloat(sin(angle))
              p.move(to: CGPoint(x: cx + w * 0.31 * dx, y: cy + h * 0.31 * dy))
              p.addLine(to: CGPoint(x: cx + w * 0.46 * dx, y: cy + h * 0.46 * dy))
            }
          }
          .stroke(Color(red: 1.0, green: 0.95, blue: 0.46), style: StrokeStyle(lineWidth: w * 0.09, lineCap: .round))
        }

      default:
        ZStack {
          RoundedRectangle(cornerRadius: w * 0.14, style: .continuous)
            .stroke(Color.white, lineWidth: w * 0.08)
            .frame(width: w * 0.72, height: h * 0.54)
            .position(x: w * 0.50, y: h * 0.51)
          Circle()
            .fill(Color.white)
            .frame(width: w * 0.16, height: h * 0.16)
            .position(x: w * 0.50, y: h * 0.58)
          Path { p in
            p.addArc(
              center: CGPoint(x: w * 0.50, y: h * 0.56),
              radius: w * 0.18,
              startAngle: .degrees(215),
              endAngle: .degrees(325),
              clockwise: false
            )
            p.move(to: CGPoint(x: w * 0.36, y: h * 0.86))
            p.addLine(to: CGPoint(x: w * 0.64, y: h * 0.86))
          }
          .stroke(Color.white, style: StrokeStyle(lineWidth: w * 0.075, lineCap: .round))
        }
      }
    }
  }
}

private struct FourPointSparkleShape: Shape {
  let center: CGPoint
  let outer: CGFloat
  let inner: CGFloat

  func path(in rect: CGRect) -> Path {
    var path = Path()
    for i in 0..<8 {
      let r = (i % 2 == 0) ? outer : inner
      let angle = -Double.pi / 2.0 + Double(i) * (Double.pi / 4.0)
      let pt = CGPoint(x: center.x + r * CGFloat(cos(angle)), y: center.y + r * CGFloat(sin(angle)))
      if i == 0 { path.move(to: pt) } else { path.addLine(to: pt) }
    }
    path.closeSubpath()
    return path
  }
}
