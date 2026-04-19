Feature: Capability detection

  Background:
    Given clear SharedPreferences to ensure test isolation

  Scenario: test capability detection display
    Given wait for app to be ready
    When go to Settings
    And enable Virtual Drive
    And go back to Device List
    And select Virtual Drive
    Then verify Hardware Information is displayed
    And verify Capability Badges
    And verify Read Offset
