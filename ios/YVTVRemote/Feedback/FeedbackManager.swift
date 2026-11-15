import AudioToolbox
import UIKit

enum FeedbackCue {
  case navTick
  case confirmChime
  case volumeStep
  case backHome
  case powerOrApp
}

@MainActor
final class FeedbackManager {
  static let shared = FeedbackManager()

  private let lightImpact = UIImpactFeedbackGenerator(style: .light)
  private let mediumImpact = UIImpactFeedbackGenerator(style: .medium)
  private let heavyImpact = UIImpactFeedbackGenerator(style: .heavy)

  private init() {
    lightImpact.prepare()
    mediumImpact.prepare()
    heavyImpact.prepare()
  }

  func trigger(_ cue: FeedbackCue, settings: AppSettings) {
    if settings.hapticsEnabled {
      switch cue {
      case .navTick, .volumeStep:
        lightImpact.impactOccurred()
      case .confirmChime, .backHome:
        mediumImpact.impactOccurred()
      case .powerOrApp:
        heavyImpact.impactOccurred()
      }
    }

    if settings.soundEnabled {
      switch cue {
      case .navTick, .volumeStep:
        AudioServicesPlaySystemSound(1104)
      case .confirmChime, .backHome:
        AudioServicesPlaySystemSound(1105)
      case .powerOrApp:
        AudioServicesPlaySystemSound(1103)
      }
    }
  }
}
