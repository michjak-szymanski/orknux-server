# Contributing to Orknux

Bug reports, ideas and patches are all welcome.

## The licence

Orknux is licensed under the **GNU Affero General Public License, version 3 or
later**, with one additional term under section 7(b) requiring the attribution
shown in the interface to be preserved. See [LICENSE](LICENSE) for the licence
and [NOTICE](NOTICE) for the additional term and what it means in practice.

Anything you contribute is contributed under those same terms.

## The contributor agreement

**By contributing you agree to all four points below.** They are short, and none
of them take anything away from you.

1. You wrote the contribution yourself, or you have the right to submit it and
   it carries no terms that conflict with this agreement.
2. You licence it to the project under the **AGPL-3.0-or-later**, with the
   section 7(b) attribution term.
3. You grant **Michał Szymański** a perpetual, worldwide, irrevocable,
   non-exclusive right to license your contribution under **other terms as
   well**, including a commercial licence, and to sublicense it as part of
   Orknux.
4. You confirm that nothing in the contribution is subject to a patent, a
   trademark or an employment agreement that would prevent points 2 and 3.

### Why point 3 exists

Orknux is dual licensed. It is free software under the AGPL, and a paid
commercial licence is available for organisations that cannot accept the AGPL's
source obligations or the attribution term. That is what funds the work, and it
only stays possible while **one person can license the entire codebase**.

If part of the code were licensed to the project under the AGPL alone, nobody —
including the author — could offer the whole of it commercially without going
back to every contributor and asking. In practice that means it never happens
again, and the paid tier that pays for the project quietly dies.

### What point 3 does not do

You keep the copyright in what you wrote. You may use your own contribution
anywhere else, under any terms you like, for anything at all. The grant is
**additional and non-exclusive** — it is not a transfer, and it does not stop
you doing anything with your own work.

### How to signify agreement

Put this line in the description of your pull request:

> I have read CONTRIBUTING.md and I agree to the contributor agreement.

and sign your commits off, which records the same thing in the history:

```bash
git commit -s -m "..."
```

That is the whole ceremony. There is no form and no account to create.

### If you would rather not agree

Say so in the pull request rather than staying quiet about it. Nothing bad
happens: for a small fix it is usually easy to accept as AGPL-only, or to take
the bug report and write the fix separately. For anything larger it is a
conversation worth having **before** you spend the time, because a contribution
that cannot be relicensed constrains the project permanently.

## For maintainers: the discipline

This part is the point of the file. The agreement above is worth nothing if it
is not applied consistently, and the failure is silent — nothing breaks on the
day a contribution lands without it. It surfaces years later, when the whole
codebase can no longer be licensed commercially and the reason is one merge
nobody remembers.

**The rules:**

1. **Do not merge without a recorded agreement.** Not "I'll ask later", not
   "they clearly meant to". If it is not in the pull request or the commit
   trailers, it did not happen.
2. **No exception for small changes.** A one-line fix is copyrightable, and a
   codebase is unlicensable if *any* part of it is. The size of the patch has
   nothing to do with it. This is the exception everybody makes and it is the
   one that causes the damage.
3. **Squash-merging does not launder anything.** Rewriting a commit does not
   change who holds the copyright in the lines it contains.
4. **Keep the record.** The pull request thread and the signed-off commits are
   the evidence. Do not delete the branch's history, and do not force-push over
   the agreement.
5. **Suggestions are not contributions.** An idea, a bug report, or a comment
   saying what is wrong carries no copyright worth worrying about. Code does —
   including code pasted into an issue comment, which should be treated exactly
   like a pull request.
6. **Do not paste code from elsewhere**, including from an answer site or a
   model, unless its licence is compatible *and* the origin is recorded in the
   pull request. Point 1 of the agreement is only true if this is true.
7. **If someone declines the agreement**, either accept the change as AGPL-only
   and record that decision permanently in this file, or reimplement it
   independently from the description of the problem. Do not merge it quietly
   and hope.

If the project ever grows past what one person can track this way, move to a
CLA bot that records assent against each pull request. Until then, the rules
above are the mechanism.

**Contributions accepted as AGPL-only, if any, are listed here.** An empty list
means the whole codebase is licensable by one person, which is the state worth
keeping.

- *(none)*

## Practical notes

- Match the surrounding code. The comment density here is deliberate: comments
  explain *why*, and a change that removes the reasoning is a change that will
  be made again wrongly in six months.
- Run the build before opening a pull request: `./mvnw verify` here, and
  `npm run typecheck` in the interface.
- Migrations are append-only. Add `V<next>__what_it_does.sql`; never edit one
  that has run anywhere.
