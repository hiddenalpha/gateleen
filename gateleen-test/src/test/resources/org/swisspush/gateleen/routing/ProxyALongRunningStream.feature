
Feature: Gateleen can proxy long running streams

  Scenario: Place a single hook and put a single PUT
    Given Mock server is running
    Then Gateleen does not interrupt a running stream
    Then Gateleen relays correct content from upstream
    Given Mock server is shut down
