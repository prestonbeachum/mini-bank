# Changelog

## [0.1.1](https://github.com/prestonbeachum/mini-bank/compare/mini-bank-v0.1.0...mini-bank-v0.1.1) (2026-11-12)


### Bug Fixes

* **build:** generate the CycloneDX SBOM again ([#6](https://github.com/prestonbeachum/mini-bank/issues/6)) ([548120d](https://github.com/prestonbeachum/mini-bank/commit/548120dab55fbab8058d5b622bb8d76e3cc7a2d3))

## 0.1.0 (2026-11-12)


### ⚠ BREAKING CHANGES

* ops opens accounts with POST /v1/customers/{customerId}/accounts; POST /v1/accounts is customer-only and rejects customer_id.

### Features

* phase 1 ledger core ([161553f](https://github.com/prestonbeachum/mini-bank/commit/161553f05239455e9df526118e8bc7fffed33069))
* phase 2 CI and packaging ([#3](https://github.com/prestonbeachum/mini-bank/issues/3)) ([3c56a67](https://github.com/prestonbeachum/mini-bank/commit/3c56a677f14e3731802abb6e19488adeb6ff25ab))
