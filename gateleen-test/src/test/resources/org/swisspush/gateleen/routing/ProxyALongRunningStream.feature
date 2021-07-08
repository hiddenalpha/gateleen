
Feature: Gateleen can proxy long running streams

  Scenario: Client expects complete response while dawdle downloading a large resource
    Given Mock server is running
    Then Gateleen does not interrupt a running stream
    Given Mock server is shut down

  Scenario: Client expects correct response while dawdle downloading a large resource
    Given Mock server is running
    Then Gateleen relays correct content from upstream
    Given Mock server is shut down
