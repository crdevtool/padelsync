import XCTest

/// Drives the iPhone app through a whole match the way a player would, and
/// saves a screenshot of every screen. Run on a simulator by the build
/// server, so the iPhone app can be reviewed without a Mac or an iPhone.
///
/// Bluetooth is not exercised: simulators have none.
final class WalkthroughTests: XCTestCase {

    private let app = XCUIApplication()
    private var step = 0

    /// Screenshots are also written here, with readable names.
    private let shotsDirectory = URL(fileURLWithPath: "/tmp/padelsync-shots", isDirectory: true)

    override func setUp() {
        continueAfterFailure = true
        try? FileManager.default.createDirectory(at: shotsDirectory, withIntermediateDirectories: true)
        // If the system asks for Bluetooth permission, answer it so the
        // walkthrough is not left staring at an alert.
        addUIInterruptionMonitor(withDescription: "System permission alert") { alert in
            for title in ["Allow", "OK", "Don\u{2019}t Allow"] where alert.buttons[title].exists {
                alert.buttons[title].tap()
                return true
            }
            return false
        }
        // Stills the animations that never end (the bouncing ball, the
        // confetti), which would otherwise keep every tap waiting for the
        // app to come to rest.
        // A simulator cannot open a court. `-pretendCourtOpen` lets the match
        // screen be photographed as it looks with one open.
        app.launchArguments += ["-stillAnimations", "-pretendCourtOpen"]
        app.launch()
    }

    func testAFullMatch() {
        // Home
        XCTAssertTrue(app.buttons["New match"].waitForExistence(timeout: 30), "home screen did not appear")
        XCTAssertTrue(app.buttons["Host a match"].exists, "the Host a match entry is missing")
        shot("home")

        // Match setup
        app.buttons["New match"].tap()
        XCTAssertTrue(app.buttons["Start match"].waitForExistence(timeout: 10))
        shot("setup-defaults")
        choose("Tennis")
        choose("Best of 5")
        // Short sets played on without a tiebreak, stopped at seven games.
        choose("4 games")
        choose("Advantage set")
        choose("First to 7")
        shot("setup-advantage-set")
        choose("Match tiebreak")
        choose("Team B")
        shot("setup-changed")
        // The match below is scripted for golden point and a tiebreak set,
        // which are no longer what the form starts with.
        choose("Padel")
        choose("1 set")
        choose("6 games")
        choose("Golden point")
        choose("Tiebreak")
        choose("Team A")
        shot("setup-one-set-padel")
        app.buttons["Start match"].tap()

        // Scoring
        let teamA = panel("Team A")
        let teamB = panel("Team B")
        // The first announcement loads the voice, which can take a moment.
        XCTAssertTrue(teamA.waitForExistence(timeout: 30), "scoreboard did not appear")
        shot("score-start")

        tap(teamA, times: 3)
        tap(teamB, times: 3)
        XCTAssertTrue(app.staticTexts["GOLDEN POINT"].waitForExistence(timeout: 10), "golden point was not called out")
        shot("golden-point")

        tap(teamB, times: 1)
        XCTAssertTrue(label(of: "Team B").contains("Games 1"), "Team B should have won the game")
        shot("game-to-team-b")

        app.buttons["Undo"].tap()
        XCTAssertTrue(label(of: "Team B").contains("Points 40"), "undo should restore 40-40")
        shot("after-undo")

        // Team A wins the golden point, then five more games for the set and match.
        tap(teamA, times: 1)
        tap(teamA, times: 19)
        XCTAssertTrue(label(of: "Team A").contains("Games 5"), "Team A should lead 5-0")
        shot("match-point")
        tap(teamA, times: 1)

        // The result screen comes up by itself.
        XCTAssertTrue(app.staticTexts["Team A win!"].waitForExistence(timeout: 5), "the match should be won")
        shot("match-won")
        choose("Scoreboard")
        XCTAssertTrue(app.staticTexts["TEAM A WON"].waitForExistence(timeout: 5), "the final scoreboard is missing")
        shot("final-scoreboard")

        // The match sheets.
        openMenu()
        shot("menu")
        app.buttons["Voice"].tap()
        if app.buttons["Done"].waitForExistence(timeout: 5) {
            shot("voice")
            app.buttons["Done"].tap()
        }
        openMenu()
        app.buttons["Who can score"].tap()
        if app.buttons["Done"].waitForExistence(timeout: 5) {
            shot("who-can-score")
            app.buttons["Done"].tap()
        }
        openMenu()
        app.buttons["Players"].tap()
        if app.buttons["Cancel"].waitForExistence(timeout: 5) {
            shot("players")
            app.buttons["Cancel"].tap()
        }

        // Sharing needs Bluetooth, which a simulator does not have. The app
        // should say so rather than misbehave.
        openMenu()
        app.buttons["Play with others"].tap()
        sleep(3)
        shot("play-with-others-without-bluetooth")

        // Once the problem has made way, the status line as it looks with a
        // court open (see `-pretendCourtOpen`), and under it the reminder to
        // keep the app on screen.
        let courtOpen = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Court open")).firstMatch
        XCTAssertTrue(courtOpen.waitForExistence(timeout: 20), "the court-open status line did not appear")
        XCTAssertTrue(
            app.staticTexts["Keep PadelSync on screen so others can join."].exists,
            "the host is not told to keep the app on screen"
        )
        shot("court-open-keep-on-screen")

        // End the match and look at the history.
        openMenu()
        app.buttons["End match"].firstMatch.tap()
        shot("end-match-confirmation")
        let confirm = app.buttons.matching(NSPredicate(format: "label == %@", "End match"))
        confirm.element(boundBy: confirm.count - 1).tap()

        XCTAssertTrue(app.buttons["Match history"].waitForExistence(timeout: 10), "did not return to the home screen")
        shot("home-again")
        choose("Match history")
        XCTAssertTrue(
            app.staticTexts["Team A beat Team B"].waitForExistence(timeout: 5),
            "the match is missing from history"
        )
        shot("history")
        app.buttons["Back"].tap()

        // Hosting asks who may score.
        choose("Host a match")
        XCTAssertTrue(app.buttons["Start and open the court"].waitForExistence(timeout: 5))
        shot("host-setup")
        app.buttons["Back"].tap()

        // Joining also needs Bluetooth.
        choose("Join a court")
        sleep(3)
        shot("join-without-bluetooth")
        app.buttons["Back"].tap()
        XCTAssertTrue(app.buttons["New match"].waitForExistence(timeout: 5))
    }

    // MARK: Helpers

    /// A team's half of the scoreboard, found by its spoken description.
    private func panel(_ team: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "\(team). Points")).firstMatch
    }

    private func label(of team: String) -> String {
        panel(team).label
    }

    /// Opens the scoreboard menu. Tapped by position: the test framework
    /// cannot "scroll to" a SwiftUI menu button, though a finger taps it fine.
    private func openMenu() {
        let menu = app.buttons["Menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 5), "the Menu button is missing")
        menu.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        _ = app.buttons[app.buttons["End match"].exists ? "End match" : "Leave court"].waitForExistence(timeout: 5)
    }

    /// Taps a button that may have to be scrolled into view first: the
    /// setup form and the result screen are taller than a small phone.
    private func choose(_ title: String) {
        let button = app.buttons[title]
        XCTAssertTrue(button.waitForExistence(timeout: 5), "the \(title) button is missing")
        var swipes = 0
        while !button.isHittable && swipes < 4 {
            app.swipeUp()
            swipes += 1
        }
        while !button.isHittable && swipes < 12 {
            app.swipeDown()
            swipes += 1
        }
        button.tap()
    }

    private func tap(_ element: XCUIElement, times: Int) {
        for _ in 0..<times { element.tap() }
    }

    /// Saves a screenshot both as a test attachment and as a named file.
    private func shot(_ name: String) {
        step += 1
        let fileName = String(format: "%02d-%@", step, name)
        let screenshot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = fileName
        attachment.lifetime = .keepAlways
        add(attachment)
        try? screenshot.pngRepresentation.write(to: shotsDirectory.appendingPathComponent("\(fileName).png"))
    }
}
