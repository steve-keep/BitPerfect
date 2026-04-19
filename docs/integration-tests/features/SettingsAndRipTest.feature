Feature: Settings and rip

  Background:
    Given clear SharedPreferences to ensure test isolation

  Scenario: test virtual drive toggle and selection
    When go to Settings (using the bottom navigation tab)
    And toggle "Enable Virtual Drive"
    And check if "Selected Test CD" header appeared
    And select a different CD
    And go back
    And check if Virtual Drive appears in Device List
    And select Virtual Drive
    And wait for Track List to load
    Then assert Track 1 and Audio badge exist

  Scenario: test metadata selection UI
    When enable Virtual Drive
    And select Virtual Drive
    And wait for Track List to load
    And wait for potential network call for metadata, or until it defaults to "Proceed with unnamed tracks" if multiple
    And click "Proceed with unnamed tracks" if sheet appears
    And wait for Track List to load
    Then track List is displayed

  Scenario: test start rip crash
    When enable Virtual Drive
    And wait for Device List and select Virtual Drive
    And start Rip
    Then verify no crash and progress starts
