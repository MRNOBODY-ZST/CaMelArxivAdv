import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMigrationReconciliationStore, MIGRATION_RECOVERY_KEY, serializeReconciliationRecord, type MigrationReconciliationRecord } from '../template-migrations.reconciliation'

const record: MigrationReconciliationRecord = {
  protocol: 'campaign-template-migration', version: 1,
  actor: '10000000-0000-0000-0000-000000000001', source: '20000000-0000-0000-0000-000000000001',
  command: { expectedLockVersion: 3, targetTemplateId: '30000000-0000-0000-0000-000000000001', targetTemplateVersionId: '40000000-0000-0000-0000-000000000001', expectedTemplateLockVersion: 4, recipientIds: ['50000000-0000-0000-0000-000000000001'], expectedMembershipDigest: 'a'.repeat(64), idempotencyKey: 'original_key' },
}
function lockHarness() {
  let tail = Promise.resolve()
  const request = vi.fn((_name: string, _options: unknown, callback: () => unknown) => {
    const result = tail.then(callback); tail = result.then(() => undefined, () => undefined); return result
  })
  vi.stubGlobal('navigator', { locks: { request } })
  return request
}
beforeEach(() => { globalThis.localStorage.clear(); lockHarness() })
afterEach(() => vi.unstubAllGlobals())

describe('durable migration reconciliation', () => {
  it('persists only exact metadata and restores a fresh store without execution', async () => {
    const first = createMigrationReconciliationStore()
    expect((await first.reserve(record)).created).toBe(true)
    const raw = globalThis.localStorage.getItem(MIGRATION_RECOVERY_KEY)!
    expect(JSON.parse(raw)).toEqual(record)
    expect(raw).not.toMatch(/accessToken|refreshToken|password|email|html|subject|https?:/)
    const fresh = createMigrationReconciliationStore()
    expect(await fresh.refresh()).toEqual({ kind: 'pending', record })
    expect((await fresh.reserve({ ...record, command: { ...record.command, idempotencyKey: 'new_key' } })).created).toBe(false)
    expect(globalThis.localStorage.getItem(MIGRATION_RECOVERY_KEY)).toBe(raw)
  })
  it('atomic competing tabs reserve at most one record even with different actors', async () => {
    const first = createMigrationReconciliationStore(), second = createMigrationReconciliationStore()
    const other = { ...record, actor: '10000000-0000-0000-0000-000000000002', command: { ...record.command, idempotencyKey: 'other_key' } }
    const answers = await Promise.all([first.reserve(record), second.reserve(other)])
    expect(answers.map((a) => a.created)).toEqual([true, false])
    expect(await second.refresh()).toEqual({ kind: 'pending', record })
  })
  it('conditional clearing cannot erase a newer or different payload', async () => {
    const store = createMigrationReconciliationStore(); await store.reserve(record)
    const other = { ...record, command: { ...record.command, expectedLockVersion: 5 } }
    globalThis.localStorage.setItem(MIGRATION_RECOVERY_KEY, serializeReconciliationRecord(other)!)
    expect(await store.clearMatched(record)).toBe(false)
    expect(await createMigrationReconciliationStore().refresh()).toEqual({ kind: 'pending', record: other })
  })
  it('clears only exact actor source key and full payload with verified remove', async () => {
    const store = createMigrationReconciliationStore(); await store.reserve(record)
    expect(await store.clearMatched(record)).toBe(true)
    expect(globalThis.localStorage.getItem(MIGRATION_RECOVERY_KEY)).toBeNull()
    expect(await store.refresh()).toEqual({ kind: 'empty' })
  })
  it('does not erase the original record if actor authority changed before lock acquisition', async () => {
    const store = createMigrationReconciliationStore(); await store.reserve(record)
    expect(await store.clearMatched(record, () => false)).toBe(false)
    expect(await createMigrationReconciliationStore().refresh()).toEqual({ kind: 'pending', record })
  })
  it('tab B explicit matching terminal reconciliation releases a known absent slot without writes', async () => {
    const first = createMigrationReconciliationStore(), second = createMigrationReconciliationStore()
    await first.reserve(record); await second.refresh()
    await first.clearMatched(record)
    expect(await second.refresh()).toEqual({ kind: 'blocked', reason: 'CHANGED' })
    const remove = vi.spyOn(Storage.prototype, 'removeItem'), write = vi.spyOn(Storage.prototype, 'setItem')
    expect(await second.clearMatched(record, () => true, true)).toBe(true)
    expect(remove).not.toHaveBeenCalled(); expect(write).not.toHaveBeenCalled()
    expect(await second.refresh()).toEqual({ kind: 'empty' })
    remove.mockRestore(); write.mockRestore()
  })
  it('tab B matching old result cannot erase a newer slot after another tab clears', async () => {
    const first = createMigrationReconciliationStore(), second = createMigrationReconciliationStore()
    await first.reserve(record); await second.refresh(); await first.clearMatched(record)
    const newer = { ...record, command: { ...record.command, idempotencyKey: 'newer_key' } }
    await first.reserve(newer)
    expect(await second.clearMatched(record, () => true, true)).toBe(false)
    expect(await createMigrationReconciliationStore().refresh()).toEqual({ kind: 'pending', record: newer })
  })
  it('an external removal never makes a known unresolved submission available for new keys', async () => {
    const store = createMigrationReconciliationStore(); await store.reserve(record)
    globalThis.localStorage.removeItem(MIGRATION_RECOVERY_KEY)
    expect(await store.refresh()).toEqual({ kind: 'blocked', reason: 'CHANGED' })
    expect((await store.reserve(record)).created).toBe(false)
  })
  it.each(['{', '{}', 'null', JSON.stringify({ ...record, version: 2 }), JSON.stringify({ ...record, accessToken: 'forbidden' }), JSON.stringify(record).replace('"version":1', '"version":1,"version":1')])('corrupt or unknown data is held, never silently discarded: %s', async (raw) => {
    globalThis.localStorage.setItem(MIGRATION_RECOVERY_KEY, raw)
    const store = createMigrationReconciliationStore()
    expect(await store.refresh()).toEqual({ kind: 'blocked', reason: 'INVALID' })
    expect((await store.reserve(record)).created).toBe(false)
    expect(globalThis.localStorage.getItem(MIGRATION_RECOVERY_KEY)).toBe(raw)
  })
  it.each([
    { ...record, actor: 'not-a-uuid' },
    { ...record, source: '' },
    { ...record, command: { ...record.command, expectedLockVersion: 1.1 } },
    { ...record, command: { ...record.command, expectedLockVersion: -1 } },
    { ...record, command: { ...record.command, expectedTemplateLockVersion: Number.MAX_SAFE_INTEGER + 1 } },
    { ...record, command: { ...record.command, idempotencyKey: 'bad key' } },
    { ...record, command: { ...record.command, expectedMembershipDigest: 'A'.repeat(64) } },
    { ...record, command: { ...record.command, recipientIds: [record.command.recipientIds[0], record.command.recipientIds[0]] } },
    { ...record, command: { ...record.command, recipientIds: Array(121).fill(record.command.recipientIds[0]) } },
    { ...record, command: { ...record.command, recipientIds: Array(1) } },
    { ...record, command: { ...record.command, email: 'forbidden@example.test' } },
  ])('rejects invalid protocol metadata before storage creation', async (invalid) => {
    expect(serializeReconciliationRecord(invalid)).toBeNull()
    expect((await createMigrationReconciliationStore().reserve(invalid as MigrationReconciliationRecord)).created).toBe(false)
    expect(globalThis.localStorage.getItem(MIGRATION_RECOVERY_KEY)).toBeNull()
  })
  it('unavailable Web Locks has no non-atomic fallback', async () => {
    vi.stubGlobal('navigator', {})
    const store = createMigrationReconciliationStore()
    expect(await store.refresh()).toEqual({ kind: 'blocked', reason: 'UNAVAILABLE' })
    expect((await store.reserve(record)).created).toBe(false)
    expect(globalThis.localStorage.getItem(MIGRATION_RECOVERY_KEY)).toBeNull()
  })
  it('storage read/write failures fail closed without replacing existing state', async () => {
    const raw = serializeReconciliationRecord(record)!
    vi.stubGlobal('localStorage', { getItem: () => { throw new Error('denied') }, setItem: vi.fn(), removeItem: vi.fn() })
    expect(await createMigrationReconciliationStore().refresh()).toEqual({ kind: 'blocked', reason: 'STORAGE' })
    vi.stubGlobal('localStorage', { getItem: () => null, setItem: () => { throw new Error('quota') }, removeItem: vi.fn() })
    expect((await createMigrationReconciliationStore().reserve(record)).snapshot).toEqual({ kind: 'blocked', reason: 'STORAGE' })
    let current = raw
    vi.stubGlobal('localStorage', { getItem: () => current, setItem: vi.fn(), removeItem: () => { current = raw } })
    expect(await createMigrationReconciliationStore().clearMatched(record)).toBe(false)
    expect(current).toBe(raw)
  })
  it('read-after-write confirmation prevents POST permission from an unpersisted record', async () => {
    vi.stubGlobal('localStorage', { getItem: () => null, setItem: vi.fn(), removeItem: vi.fn() })
    expect(await createMigrationReconciliationStore().reserve(record)).toEqual({ created: false, snapshot: { kind: 'blocked', reason: 'STORAGE' } })
  })
})
