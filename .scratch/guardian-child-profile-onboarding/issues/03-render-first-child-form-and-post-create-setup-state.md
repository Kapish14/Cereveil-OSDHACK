Status: ready-for-agent

# Render first-child form and post-create setup state

## Parent

.scratch/guardian-child-profile-onboarding/PRD.md

## What to build

Build the Guardian setup UI flow around the Child Profile create/list capabilities. When Guardian setup has no active Child Profiles, show a first-child form that collects only display name and birth month/year. On successful create, refresh setup state and show the resulting Unenrolled Child Profile with a clear "set up child device" next-action placeholder. This slice must not generate an Enrollment Code or begin Child Mode enrollment.

## Acceptance criteria

- [ ] Guardian setup shows the first-child form when the authenticated Household has no active Child Profiles.
- [ ] The form collects display name, birth month, and birth year only.
- [ ] The form does not ask for avatar, legal name, exact birth date, email, phone, or Child login credentials.
- [ ] UI/client validation catches blank display names and invalid month/year values before or during submission.
- [ ] Out-of-range age errors are shown as stable user-facing form errors.
- [ ] Successful submission calls the fakeable Child Profile create seam with only display name, birth month, and birth year.
- [ ] After successful create, setup state refreshes without requiring app restart.
- [ ] The created Unenrolled Child Profile is visible after creation.
- [ ] The post-create state makes clear that the Child Device is not yet actively supervised.
- [ ] The post-create state shows a "set up child device" placeholder or equivalent next action.
- [ ] The placeholder does not generate an Enrollment Code, show a QR code, or invoke Child Mode enrollment.
- [ ] Retryable network failures are recoverable without forcing the Guardian through auth again.
- [ ] Raw backend exception payloads and internal identifiers are not displayed in UI.
- [ ] Tests cover empty setup rendering, form submission, validation/error states, successful post-create state, and absence of Enrollment Code behavior.

## Blocked by

- .scratch/guardian-child-profile-onboarding/issues/01-create-unenrolled-child-profile-with-initial-supervision-policy.md
- .scratch/guardian-child-profile-onboarding/issues/02-list-child-profiles-for-guardian-setup.md
