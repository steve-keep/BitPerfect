Feature: Example instrumented

  Scenario: use app context
    Given context of the app under test
    Then package name is "com.bitperfect.app"
