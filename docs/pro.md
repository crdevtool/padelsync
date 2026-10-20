# PadelSync Pro

Hosting a court for others needs Pro. Everything else stays free.

| Free | Pro |
| --- | --- |
| New match: score on one phone | Host a match: open a court others join |
| Join a court, and score on it | "Play with others" from a match on this device |
| Watch apps, history, all formats | |

Only the host pays: one Pro covers everyone on the court, and anyone can
join without paying. That is the line to sell: one of you pays, all four see
the score.

## Trial

Every phone and watch gets a 14-day trial of its own, counted from the first
time it opens a court. No card, no store: the start date is kept on the
device. Reinstalling the app starts a new trial; that is accepted, as the
trial is there to let a group try hosting, not to guard revenue.

Once the trial is over, "Host a match" (and "Play with others") opens the
Pro sheet. A court opened during the trial or with Pro may still be resumed
after it ends, so a match in progress is never cut off.

The rules are in the shared core, `core/.../pro/ProAccess.kt`, so the apps
agree on them. Its tests are `ProAccessTest`.

## Plans and prices

| Plan | Price (USD tier) | Google Play | App Store |
| --- | --- | --- | --- |
| Monthly | 1.99 | subscription `pro`, base plan `monthly` (auto-renewing, 1 month) | auto-renewable `padelsync.pro.monthly`, 1 month |
| Yearly | 9.99 | subscription `pro`, base plan `yearly` (auto-renewing, 1 year) | auto-renewable `padelsync.pro.yearly`, 1 year |
| Lifetime | 24.99 | one-time product `pro_lifetime` | non-consumable `padelsync.pro.lifetime` |

The stores turn the USD price into each country's own; the app shows the
store's price as it comes. The yearly plan's "SAVE 58%" and "$0.83 a month"
are worked out from the store's prices, so they stay right in any currency.

A purchase belongs to the store account: on Android it covers the phone and
the watch on the same Google account; on Apple, every iPhone on the same
Apple Account. It does not carry between Android and iPhone; that would need
player accounts and a server.

## The code

- Android: `androidkit/.../ProStore.kt` (Play Billing Library 8), used by the
  phone (`HomeScreen`, `ProSheet`, `AppRoot`) and the watch (menu, "Play with
  others"). The watch does not sell: it tells the player to buy on the phone,
  and sees the purchase through the same Google account.
- iPhone: `apple/iOS/ProStore.swift` (StoreKit 2) and `ProSheetView.swift`.
  The Apple Watch cannot host, so it has nothing to sell.
- The answer from the store is also kept on the device, so a court can be
  hosted with no connection.
- Tests: the phone emulator run and the iPhone walkthrough start debug builds
  with the trial ended (`--ez trial_ended true`, `-trialEnded`) and photograph
  the home screen and the Pro sheet. The emulators and simulators have no
  store, so the sheet there says the store is not available.

## Setting it up in the stores (done by hand, once)

### Google Play Console

1. **Payments profile.** Settings > Payments profile: make sure a merchant
   account exists with your bank details. Without it, products cannot be
   priced.
2. **Upload a build with billing first.** Play Console only offers
   Monetize > Products once the app has a build that uses the billing
   library. Upload the release 10 bundles to the closed test as usual.
3. **Subscription.** Monetize > Products > Subscriptions > Create
   subscription: Product ID `pro`, name `PadelSync Pro`. Inside it add two
   base plans:
   - `monthly`: auto-renewing, billing period 1 month, price USD 1.99,
     then "Update prices" for all countries from it.
   - `yearly`: auto-renewing, billing period 1 year, price USD 9.99.
   Activate both base plans. Add no offers (the trial is in the app).
4. **Lifetime.** Monetize > Products > In-app products > Create product:
   Product ID `pro_lifetime`, name `PadelSync Pro (lifetime)`, description
   `Host matches for ever: every phone and watch on the court shows the
   score.`, price USD 24.99. Activate it.
5. **License testers.** Settings > License testing: add the Google accounts
   of the testers. Their purchases are test purchases, never charged.

The product IDs cannot be reused once created, so type them exactly.

### App Store Connect

1. **Paid Apps Agreement.** Business > Agreements: accept the Paid Apps
   agreement and fill in the bank account and tax forms. Products cannot be
   bought until it is Active.
2. **Subscription group.** Your app > Monetization > Subscriptions >
   Create a group named `PadelSync Pro`, with an App Store localization
   (display name `PadelSync Pro`).
3. **Two subscriptions in the group**, each with a localization (display
   name and description) and a price; leave the review screenshot until the
   App Store submission:
   - Reference name `Pro Monthly`, product ID `padelsync.pro.monthly`,
     duration 1 month, price USD 1.99.
   - Reference name `Pro Yearly`, product ID `padelsync.pro.yearly`,
     duration 1 year, price USD 9.99.
4. **Lifetime.** Monetization > In-App Purchases > Create: type
   Non-Consumable, reference name `Pro Lifetime`, product ID
   `padelsync.pro.lifetime`, price USD 24.99, with a localization.
5. **TestFlight.** Purchases made in a TestFlight build are free sandbox
   purchases, so the group can try buying without paying. Products work in
   TestFlight once their status is "Ready to Submit".
6. **App Store release.** The first in-app purchases go to Apple's review
   together with an app version, each with a review screenshot of the Pro
   sheet.

### Privacy and listings

Purchases are handled by Google and Apple; the app sends nothing to any
server of ours, so the data safety form and the App Privacy answers do not
change. The Pro sheet links to the privacy policy
(`ProText.PRIVACY_URL`) and, on iPhone, to Apple's standard terms of use, as
Apple requires next to a subscription.
