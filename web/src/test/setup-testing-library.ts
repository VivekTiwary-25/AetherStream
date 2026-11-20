// Testing Library's findBy*/waitFor give up after 1 s by default. On a full
// `npx vitest run` every CPU core is busy (one jsdom worker per core), so
// lazy chunks and heavy first renders can take longer than that even though
// the same test passes in well under a second on its own. Give async queries
// more room; the per-test limit in vite.config.ts stays above this.
import { configure } from '@testing-library/react'

configure({ asyncUtilTimeout: 4000 })
