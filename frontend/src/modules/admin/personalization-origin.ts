/** Client-side counterpart of PersonalizationPolicy; DNS checks remain server-side. */
export function normalizedPersonalizationOrigin(origin: string): string | null {
  try {
    const raw = origin.trim()
    // Check the raw input before URL removes dot segments or empty query/fragment markers.
    const match = /^https:\/\/([^/?#]+)([^?#]*)$/i.exec(raw)
    if (!match || /[@%\\\s]/.test(match[1] ?? '') || /[?#\\\s]/.test(raw)) return null
    const path = match[2] ?? ''
    if (path.includes('..') || path.includes('%')) return null
    const url = new globalThis.URL(raw)
    if (url.port === '0') return null
    const host = url.hostname.toLowerCase()
    if (host.startsWith('[')) {
      if (!publicIpv6(host.slice(1, -1))) return null
    } else {
      const dnsHost = host.replace(/\.$/, '')
      if (!host.includes('.') || dnsHost === 'localhost' || dnsHost.endsWith('.localhost') || dnsHost.endsWith('.local')) return null
      if (/^[\d.]+$/.test(host) && !publicIpv4(host)) return null
    }
    return `https://${url.host.toLowerCase()}${path.replace(/\/+$/, '') || '/v1'}`
  } catch {
    return null
  }
}

function publicIpv4(host: string): boolean {
  const [a = -1, b = -1, c = -1] = host.split('.').map(Number)
  return !(a === 0 || a === 10 || a === 127 || a >= 224 ||
    (a === 100 && b >= 64 && b <= 127) || (a === 169 && b === 254) ||
    (a === 172 && b >= 16 && b <= 31) ||
    (a === 192 && (b === 0 || b === 168 || (b === 88 && c === 99))) ||
    (a === 198 && (b === 18 || b === 19 || b === 51)) || (a === 203 && b === 0))
}

function publicIpv6(host: string): boolean {
  // URL has validated and canonicalized this IPv6 literal. Only 2000::/3 is
  // global unicast; exclude protocol, documentation and transition assignments.
  const words = host.split(':')
  const first = Number.parseInt(words[0] || '0', 16)
  const second = Number.parseInt(words[1] || '0', 16)
  return first >= 0x2000 && first <= 0x3fff &&
    !(first === 0x2001 && (second < 0x2000 || second === 0x0db8)) &&
    first !== 0x2002 && !(first === 0x3fff && second < 0x1000)
}
