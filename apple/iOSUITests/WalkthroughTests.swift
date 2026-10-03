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
        app.launch()
    }

    func testAFullMatch() {
        // Home
        XCTAssertTrue(app.buttons["New match"].waitForExistence(timeout: 30), "home screen did not appear")
        shot("home")

        // Match setup
        app.buttons["New match"].tap()
        XCTAssertTrue(app.buttons["Start match"].waitForExistence(timeout: 10))
        shot("setup-defaults")
        app.buttons["Tennis"].tap()
        app.buttons["Best of 5"].tap()
        app.buttons["Match tiebreak"].tap()
        app.buttons["Team B"].tap()
        shot("setup-changed")
        app.buttons["Padel"].tap()
        app.buttons["1 set"].tap()
        app.buttons["Team A"].tap()
        shot("setup-one-set-padel")
        app.buttons["Start match"].tap()

        // Scoring
        let teamA = panel("Team A")
        let teamB = panel("Team B")
        XCTAssertTrue(teamA.waitForExistence(timeout: 10), "scoreboard did not appear")
        shot("score-start")

        tap(teamA, times: 3)
        tap(teamB, times: 3)
        XCTAssertTrue(app.staticTexts["GOLDEN POINT"].waitForExistence(timeout: 5), "golden point was not called out")
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
        XCTAssertTrue(app.staticTexts["TEAM A WINS"].waitForExistence(timeout: 5), "the match should be won")
        shot("match-won")

        // Sharing needs Bluetooth, which a simulator does not have. The app
        // should say so rather than misbehave.
        app.buttons["Menu"].tap()
        shot("menu")
        app.buttons["Play with others"].tap()
        sleep(3)
        shot("play-with-others-without-bluetooth")

        // End the match and look at the history.
        app.buttons["Menu"].tap()
        app.buttons["End match"].firstMatch.tap()
        shot("end-match-confirmation")
        let confirm = app.buttons.matching(NSPredicate(format: "label == %@", "End match"))
        confirm.element(boundBy: confirm.count - 1).tap()

        XCTAssertTrue(app.buttons["Match history"].waitForExistence(timeout: 10), "did not return to the home screen")
        app.buttons["Match history"].tap()
        XCTAssertTrue(app.staticTexts["Team A won"].waitForExistence(timeout: 5), "the match is missing from history")
        shot("history")
        app.buttons["Back"].tap()

        // Joining also needs Bluetooth.
        app.buttons["Join a court"].tap()
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
