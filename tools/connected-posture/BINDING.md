# Connected-service source policy

`tools/mimir_connected_posture_gate.py` checks source-level network boundaries against the
registered gates, request entry points and host suffixes declared in that script.

Scanning and launch dispatch must remain offline. Metadata/artwork requests require the relevant
opt-in gate. Provider routes must retain their request authorization, HTTPS host checks and
credential-safe logging. Vendored native readers must not gain network paths.

Run the gate and its self-tests after changing those boundaries. These source checks do not prove
runtime network behavior or grant permission to use a provider or redistribute its content.

The Tweaker family-catalog transport is separately bounded by its configured HTTPS origin, size
limits and signature checks. The connection-cancellation seam may close existing connections but
must not create requests. Provider onboarding labels and legal credits are permitted only through
the explicit resource-entry/path rules; they do not authorize additional network routes.
