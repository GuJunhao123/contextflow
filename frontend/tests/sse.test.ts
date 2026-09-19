import test from 'node:test'
import assert from 'node:assert/strict'
import { consumeSse } from '../src/api.ts'

const result = { requestId: 'r1', status: 'answer', message: '完成', answer: '中文证据', citations: [], trace: [], mode: 'llm' }
function stream(text: string, bytewise = false) {
  const bytes = new TextEncoder().encode(text)
  return new ReadableStream<Uint8Array>({ start(controller) {
    if (bytewise) for (const byte of bytes) controller.enqueue(Uint8Array.of(byte))
    else controller.enqueue(bytes)
    controller.close()
  } })
}
test('UTF-8 byte splits, CRLF splits, comments and multiline JSON data', async () => {
  const nodes: unknown[] = []
  const text = ': heartbeat\r\nevent: node\r\ndata: {"node":"检索","status":"ok",\r\ndata: "durationMs":1,"detail":"中文"}\r\n\r\nevent: result\r\ndata: ' + JSON.stringify(result) + '\r\n\r\n'
  assert.deepEqual(await consumeSse(stream(text, true), n => nodes.push(n)), result)
  assert.equal(nodes.length, 1)
})
test('rejects stream missing terminal event', async () => {
  await assert.rejects(consumeSse(stream(': ping\n\n'), () => {}), /缺少完整/)
})
test('rejects incomplete terminal frame', async () => {
  await assert.rejects(consumeSse(stream('event: result\ndata: ' + JSON.stringify(result)), () => {}), /缺少完整/)
})
test('rejects malformed JSON and invalid terminal structure', async () => {
  await assert.rejects(consumeSse(stream('event: result\ndata: nope\n\n'), () => {}))
  await assert.rejects(consumeSse(stream('event: result\ndata: {}\n\n'), () => {}), /结构/)
})
test('error event must carry error status', async () => {
  await assert.rejects(consumeSse(stream('event: error\ndata: ' + JSON.stringify(result) + '\n\n'), () => {}), /状态/)
  const error = { ...result, status: 'error' }
  assert.deepEqual(await consumeSse(stream('event: error\ndata: ' + JSON.stringify(error) + '\n\n'), () => {}), error)
})
test('honors cancellation and rejects invalid UTF-8', async () => {
  const controller = new AbortController(); controller.abort()
  await assert.rejects(consumeSse(stream(''), () => {}, controller.signal), { name: 'AbortError' })
  const invalid = new ReadableStream<Uint8Array>({ start(c) { c.enqueue(Uint8Array.of(0xff)); c.close() } })
  await assert.rejects(consumeSse(invalid, () => {}))
})
