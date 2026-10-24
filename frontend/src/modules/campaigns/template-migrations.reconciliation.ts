import type { MigrationCommand } from './template-migrations.types'

export const MIGRATION_RECOVERY_KEY = 'camel.campaign-template-migration.reconciliation.v1'
const LOCK_NAME = 'camel.campaign-template-migration.reconciliation'
export interface MigrationReconciliationRecord {
  protocol: 'campaign-template-migration'
  version: 1
  actor: string
  source: string
  command: MigrationCommand
}
export type ReconciliationSnapshot =
  | { kind: 'empty' }
  | { kind: 'pending'; record: MigrationReconciliationRecord }
  | { kind: 'blocked'; reason: 'UNAVAILABLE' | 'INVALID' | 'STORAGE' | 'CHANGED' }

const uuid = (value: unknown): value is string => typeof value === 'string'
  && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value)
const integer = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
function object(value: unknown, keys: string[]): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    && Object.keys(value).length === keys.length && keys.every((key) => Object.hasOwn(value, key))
}

/** Only fixed protocol metadata is serializable; no opaque extras or credentials. */
export function serializeReconciliationRecord(value: unknown): string | null {
  if (!object(value, ['protocol', 'version', 'actor', 'source', 'command'])
    || value.protocol !== 'campaign-template-migration' || value.version !== 1 || !uuid(value.actor) || !uuid(value.source)) return null
  const c = value.command
  if (!object(c, ['expectedLockVersion', 'targetTemplateId', 'targetTemplateVersionId', 'expectedTemplateLockVersion', 'recipientIds', 'expectedMembershipDigest', 'idempotencyKey'])
    || !integer(c.expectedLockVersion) || !integer(c.expectedTemplateLockVersion)
    || !uuid(c.targetTemplateId) || !uuid(c.targetTemplateVersionId)
    || typeof c.expectedMembershipDigest !== 'string' || !/^[0-9a-f]{64}$/.test(c.expectedMembershipDigest)
    || typeof c.idempotencyKey !== 'string' || !/^[A-Za-z0-9_-]{1,128}$/.test(c.idempotencyKey)
    || !Array.isArray(c.recipientIds) || c.recipientIds.length > 120 || !Array.from(c.recipientIds).every(uuid)
    || new Set(c.recipientIds).size !== c.recipientIds.length) return null
  return JSON.stringify({
    protocol: value.protocol, version: value.version, actor: value.actor, source: value.source,
    command: {
      expectedLockVersion: c.expectedLockVersion, targetTemplateId: c.targetTemplateId,
      targetTemplateVersionId: c.targetTemplateVersionId, expectedTemplateLockVersion: c.expectedTemplateLockVersion,
      recipientIds: c.recipientIds, expectedMembershipDigest: c.expectedMembershipDigest, idempotencyKey: c.idempotencyKey,
    },
  })
}

function decode(raw: string | null): ReconciliationSnapshot {
  if (raw === null) return { kind: 'empty' }
  if (raw.length > 16_384) return { kind: 'blocked', reason: 'INVALID' }
  try {
    const value: unknown = JSON.parse(raw)
    // Exact canonical bytes reject duplicate fields/truncation/unknown formats, not merely JSON.parse coercion.
    if (serializeReconciliationRecord(value) !== raw) return { kind: 'blocked', reason: 'INVALID' }
    return { kind: 'pending', record: value as MigrationReconciliationRecord }
  } catch { return { kind: 'blocked', reason: 'INVALID' } }
}

/** One origin-wide slot intentionally blocks all actors until the original actor reconciles it. */
export function createMigrationReconciliationStore() {
  let fault: ReconciliationSnapshot | null = null
  let known: string | null = null

  async function exclusive<T>(work: (storage: Storage) => T, allowChanged = false): Promise<T | ReconciliationSnapshot> {
    if (fault && !(allowChanged && fault.kind === 'blocked' && fault.reason === 'CHANGED')) return fault
    try {
      if (typeof navigator === 'undefined' || typeof navigator.locks?.request !== 'function') {
        return (fault = { kind: 'blocked', reason: 'UNAVAILABLE' })
      }
      return await navigator.locks.request(LOCK_NAME, { mode: 'exclusive' }, () => work(globalThis.localStorage))
    } catch { return (fault = { kind: 'blocked', reason: 'STORAGE' }) }
  }
  function read(storage: Storage): ReconciliationSnapshot {
    const raw = storage.getItem(MIGRATION_RECOVERY_KEY)
    const state = decode(raw)
    if (state.kind === 'blocked') return (fault = state)
    if (known !== null && raw !== known) return (fault = { kind: 'blocked', reason: 'CHANGED' })
    if (state.kind === 'pending') known = raw
    return state
  }
  async function refresh(): Promise<ReconciliationSnapshot> {
    return exclusive(read) as Promise<ReconciliationSnapshot>
  }
  async function reserve(record: MigrationReconciliationRecord): Promise<{ created: boolean; snapshot: ReconciliationSnapshot }> {
    const bytes = serializeReconciliationRecord(record)
    if (bytes === null) return { created: false, snapshot: { kind: 'blocked', reason: 'INVALID' } }
    const outcome = await exclusive((storage) => {
      const current = read(storage)
      if (current.kind !== 'empty') return { created: false, snapshot: current }
      storage.setItem(MIGRATION_RECOVERY_KEY, bytes)
      if (storage.getItem(MIGRATION_RECOVERY_KEY) !== bytes) {
        return { created: false, snapshot: (fault = { kind: 'blocked', reason: 'STORAGE' }) }
      }
      known = bytes
      return { created: true, snapshot: decode(bytes) }
    })
    return 'kind' in outcome ? { created: false, snapshot: outcome } : outcome
  }
  /** Caller must verify a terminal result or definite noncommitting rejection. No reset API exists. */
  async function clearMatched(record: MigrationReconciliationRecord, stillAuthorized: () => boolean = () => true, matchingReadback = false): Promise<boolean> {
    const expected = serializeReconciliationRecord(record)
    if (expected === null) return false
    const outcome = await exclusive((storage) => {
      if (!stillAuthorized()) return false
      const raw = storage.getItem(MIGRATION_RECOVERY_KEY)
      // Explicit matching terminal GET only: another tab may already have cleared this exact known record.
      if (matchingReadback && raw === null && known === expected) {
        known = null; fault = null
        return true // No delete/write; a future reserve must still acquire the lock and re-read.
      }
      if (raw !== expected || decode(raw).kind !== 'pending') {
        fault = { kind: 'blocked', reason: 'CHANGED' }
        return false
      }
      storage.removeItem(MIGRATION_RECOVERY_KEY)
      if (storage.getItem(MIGRATION_RECOVERY_KEY) !== null) {
        fault = { kind: 'blocked', reason: 'STORAGE' }
        return false
      }
      known = null
      fault = null
      return true
    }, matchingReadback)
    return outcome === true
  }
  return { refresh, reserve, clearMatched }
}
