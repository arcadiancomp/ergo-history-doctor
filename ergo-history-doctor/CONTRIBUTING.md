# Contributing

Changes that broaden automatic repair scope should be held to a higher standard than changes that improve diagnosis.

A repair rule should have:

1. a precise persisted-state predicate;
2. an explanation of why converting that state to clean `Absent` is safe;
3. exact precondition capture in the plan;
4. interruption/resume behavior;
5. post-write and reopen verification;
6. a regression fixture reproducing the damaged state when practical.

Please keep state repair, header-chain repair and body-section repair separate. A patch that makes the tool more aggressive should not silently widen an existing command's behavior.

Upstream fixes should be preferred when the node itself can safely recover the state. This project is intended to complement upstream Ergo maintenance, not fork its database semantics.
