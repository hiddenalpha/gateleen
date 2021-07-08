
Feature: Gateleen can proxy long running streams

  Scenario: Long running stream must work properly while rule updates
    Given Mock server is running
    Then Gateleen does not interrupt a running stream
    Then Gateleen relays correct content from upstream
    Given Mock server is shut down
