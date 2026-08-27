export interface Trace { node: string; status: string; durationMs: number; detail: string }
export interface Citation { docId: string; title: string; page: number; text: string }
export interface Result { requestId: string; status: string; message: string; answer: string; citations: Citation[]; trace: Trace[]; mode: string }
export interface Source extends Citation { model: string; yearFrom: number; yearTo: number; advice: string }
export interface DiagnosisRequest { model: string | null; year: number | null; question: string; scenario: string }
export interface Scenario { id: string; label: string; model: string; year: number; question: string }
export interface Provenance { url: string | null; title: string; locator: string; kind: 'authored-tsb'; applicability: string; retrievedAt: string | null }
export type ProvenanceMap = Record<string, Provenance>
export interface Retrieval { mode: string; corpusVersion: string; candidateLimit: number; rrfK: number; bm25: string[]; vector: string[]; top5: { chunkId: string; bm25Rank: number | null; vectorRank: number | null; rrfScore: number }[]; candidateSource: 'fixed-evidence' | 'pgvector' | 'case-library' | 'none'; matchedCase: string | null }
export interface Meta { mode: string; corpusSize: number; corpusVersion: string; limits: { timeoutMs: number }; retrieval: string; generation: string; notice: string; caseRecallEnabled: boolean }
export interface Evaluation { hitAt5: number; recallAt5: number; total: number; dataset: string; mode: string; cases: { id: string; question: string; gold: string[]; actual: string[]; hit: boolean; recall: number }[] }

export function retrievalRequest(current: DiagnosisRequest, last?: DiagnosisRequest, inspected?: DiagnosisRequest): DiagnosisRequest {
  const request = inspected ?? (current.question.trim() ? current : last)
  if (!request?.model?.trim() || request.year === null || !request.question.trim()) throw new Error('请填写车型、年份和问题，或选择已有回答的证据')
  return request
}
export async function jsonRequest<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`/api${path}`, init)
  const data: unknown = await response.json().catch(() => null)
  if (!response.ok) {
    const detail = data && typeof data === 'object' && 'message' in data ? String(data.message) : response.statusText
    throw new Error(`HTTP ${response.status}: ${detail}`)
  }
  if (data === null) throw new Error('服务端未返回有效 JSON')
  return data as T
}

function isTrace(value: unknown): value is Trace {
  if (!value || typeof value !== 'object') return false
  const t = value as Trace
  return typeof t.node === 'string' && typeof t.status === 'string' && typeof t.detail === 'string' && typeof t.durationMs === 'number'
}
function isResult(value: unknown): value is Result {
  if (!value || typeof value !== 'object') return false
  const r = value as Result
  return typeof r.requestId === 'string' && !!r.requestId && typeof r.status === 'string'
    && typeof r.message === 'string' && typeof r.answer === 'string' && typeof r.mode === 'string'
    && Array.isArray(r.trace) && r.trace.every(isTrace) && Array.isArray(r.citations)
    && r.citations.every(c => c && typeof c.docId === 'string' && typeof c.title === 'string' && typeof c.page === 'number' && typeof c.text === 'string')
}

// Incremental line parsing retains split CRLF and uses streaming UTF-8 decoding.
export async function consumeSse(body: ReadableStream<Uint8Array>, onNode: (trace: Trace) => void, signal?: AbortSignal): Promise<Result> {
  const reader = body.getReader()
  const decoder = new TextDecoder('utf-8', { fatal: true })
  let buffer = '', event = '', data: string[] = [], terminal: Result | undefined
  const dispatch = () => {
    if (!data.length) { event = ''; return }
    if (!['node', 'result', 'error'].includes(event)) throw new Error(`未知 SSE 事件：${event || 'message'}`)
    const value: unknown = JSON.parse(data.join('\n'))
    if (event === 'node') {
      if (!isTrace(value)) throw new Error('节点事件结构无效')
      onNode(value)
    } else {
      if (!isResult(value) || (event === 'error' ? value.status !== 'error' : value.status === 'error')) throw new Error('终止事件结构或状态无效')
      terminal = value
    }
    data = []; event = ''
  }
  const parse = (eof = false) => {
    while (!terminal) {
      const match = /[\r\n]/.exec(buffer)
      if (!match) break
      const i = match.index
      if (buffer[i] === '\r' && i === buffer.length - 1 && !eof) break
      const line = buffer.slice(0, i)
      buffer = buffer.slice(i + (buffer[i] === '\r' && buffer[i + 1] === '\n' ? 2 : 1))
      if (!line) { dispatch(); continue }
      if (line.startsWith(':')) continue
      const colon = line.indexOf(':')
      const field = colon < 0 ? line : line.slice(0, colon)
      const value = colon < 0 ? '' : line.slice(colon + 1).replace(/^ /, '')
      if (field === 'event') event = value
      if (field === 'data') data.push(value)
      if (buffer.length > 4_000_000 || data.join('\n').length > 4_000_000) throw new Error('SSE 事件过大')
    }
  }
  try {
    while (!terminal) {
      if (signal?.aborted) throw new DOMException('已停止', 'AbortError')
      const { done, value } = await reader.read()
      if (done) {
        buffer += decoder.decode(); parse(true)
        if (!terminal) throw new Error('SSE 提前结束：缺少完整 result/error 终止事件')
        break
      }
      buffer += decoder.decode(value, { stream: true })
      if (buffer.length > 4_000_000) throw new Error('SSE 缓冲区过大')
      parse()
    }
    return terminal!
  } finally {
    await reader.cancel().catch(() => undefined)
    reader.releaseLock()
  }
}

export async function diagnose(request: DiagnosisRequest, controller: AbortController, onNode: (trace: Trace) => void): Promise<Result> {
  const response = await fetch('/api/diagnosis/stream', {
    method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify(request), signal: controller.signal,
  })
  if (!response.ok) {
    const error = await response.json().catch(() => null)
    throw new Error(`HTTP ${response.status}: ${error?.message || response.statusText}`)
  }
  if (!response.headers.get('content-type')?.includes('text/event-stream') || !response.body) throw new Error('后端未返回 SSE 流')
  return consumeSse(response.body, onNode, controller.signal)
}
