import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parse, compileScript } from '@vue/compiler-sfc'
import ts from 'typescript'
import { createRenderer, nextTick, createSSRApp, markRaw, h } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { retrievalRequest } from '../src/api.ts'

async function component(name: string, expose = '') {
  const file = new URL(`../src/${name}.vue`, import.meta.url)
  const source = readFileSync(file, 'utf8').replace('</script>', `${expose}\n</script>`)
  const { descriptor } = parse(source)
  if (expose) descriptor.template = null
  const compiled = compileScript(descriptor, { id: name, inlineTemplate: true })
  let code = ts.transpileModule(compiled.content, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText
  code = code.replace(/from ["']([^"']+)["']/g, (_, path) => `from ${JSON.stringify(path === './api' ? new URL('../src/api.ts', import.meta.url).href : path === './SourceDetails.vue' ? 'data:text/javascript,export default {}' : import.meta.resolve(path))}`)
  return (await import(`data:text/javascript;base64,${Buffer.from(code).toString('base64')}`)).default
}
const request = { model: 'Sedan', year: 2023, question: '交流充电中断', scenario: 'normal' }
const provenance = { title: '充电系统检查', locator: '充电系统 / 连接检查', url: null, kind: 'authored-tsb', applicability: 'Sedan · 2023', retrievedAt: null }
const trace = { node: 'lucene_search', status: 'ok', durationMs: 7, detail: 'candidateSource=fixed-evidence；有序证据=2' }
const inputTrace = { node: 'input_check', status: 'ok', durationMs: 1, detail: 'answer' }
const resultTraces = [inputTrace, trace, { ...trace, node: 'evidence_check', detail: 'answer' }]

test('证据请求保留发送请求，并优先锁定历史问题', () => {
  const empty = { ...request, question: '' }
  assert.equal(retrievalRequest(empty, request), request)
  const historical = { ...request, question: '历史问题' }
  assert.equal(retrievalRequest(request, request, historical), historical)
  assert.throws(() => retrievalRequest(empty), /请填写/)
})

test('TSB资料详情仅渲染编号、章节和适用范围，不提供外部链接', async () => {
  const Details = await component('SourceDetails')
  const html = await renderToString(createSSRApp(Details, { docId: 'TSB-002', provenance: { ...provenance, url: 'https://example.com/private' } }))
  for (const text of ['TSB编号：TSB-002', '充电系统 / 连接检查', 'Sedan · 2023']) assert.ok(html.includes(text))
  assert.doesNotMatch(html, /href=|example.com|采集|官方|authored-tsb|页码/)
})

test('工作台渲染专业标签，移除管理功能与原始技术字段', async () => {
  const App = await component('App')
  const app = createSSRApp(App)
  app.config.warnHandler = () => {}
  const passthrough = { setup(_: unknown, { slots }: any) { return () => h('div', slots.default?.()) } }
  for (const name of ['a-button', 'a-tag', 'a-alert', 'a-tabs', 'a-tab-pane', 'a-empty', 'a-input', 'a-input-number', 'a-textarea']) app.component(name, passthrough)
  const html = await renderToString(app)
  for (const text of ['TSB诊断工作台', '常见问题', '诊断依据', '排查步骤', '建议结论', '安全提示']) assert.ok(html.includes(text))
  assert.doesNotMatch(html, /Tesla|MODEL3|公开资料|未调用|offline|预设|模拟|待完善|故障注入|重建索引|BM25|RRF|向量服务|原始.*JSON/i)
  const source = readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8')
  assert.doesNotMatch(source, /\/readiness|\/index\/rebuild|pretty\(|trace\.detail\s*}}|requestId\s*:/)
  assert.match(source, /序号不代表相关性评分/)
  assert.match(source, /不评估诊断正确率/)
})

test('真实SSE请求、两秒展示、证据顺序、历史锁定及错误停止均有效', async () => {
  const App = await component('App', 'defineExpose({ question, model, year, selectedCase, send, inspect, runRetrieval, runEvaluation, loadCorpus, loadMeta, selectedResult, messages, stop, traces, displayedTraces, matchingVisible, retrievalRows, nodeLabel, statusLabel, traceSummary, serviceStatus, error })')
  const previous = globalThis.fetch
  const calls: { url: string; init?: RequestInit }[] = []
  let status = 'ask_user', hold = false, failMeta = false
  globalThis.fetch = async (input, init) => {
    const url = String(input); calls.push({ url, init })
    if (url.endsWith('/diagnosis/stream')) {
      if (hold) return await new Promise<Response>((_, reject) => init!.signal!.addEventListener('abort', () => reject(new DOMException('已停止', 'AbortError'))))
      const result = { requestId: String(calls.length), status, message: status === 'error' ? '生成服务暂不可用，请重试' : '', answer: status === 'error' ? '' : '诊断依据：检查连接。排查步骤：核对接口。建议结论：联系专业人员。', citations: [], trace: resultTraces, mode: 'llm' }
      const nodes = resultTraces.map(item => `event: node\ndata: ${JSON.stringify(item)}\n\n`).join('')
      return new Response(`${nodes}event: ${status === 'error' ? 'error' : 'result'}\ndata: ${JSON.stringify(result)}\n\n`, { headers: { 'Content-Type': 'text/event-stream' } })
    }
    if (url.endsWith('/meta') && failMeta) throw new Error('服务连接中断')
    const data = url.endsWith('/scenarios') ? Array.from({ length: 8 }, (_, i) => ({ id: `case-${i}`, label: `问题${i + 1}`, ...request }))
      : url.endsWith('/meta') ? { mode: 'llm', corpusSize: 20, limits: { timeoutMs: 100 } }
      : url.endsWith('/provenance') ? { 'TSB-002': provenance, 'TSB-001': { ...provenance, title: '接口检查' } }
      : url.includes('/retrieval?') ? { candidateSource: 'fixed-evidence', top5: ['TSB-002', 'TSB-001'].map(chunkId => ({ chunkId, bm25Rank: null, vectorRank: null, rrfScore: 0 })), bm25: [], vector: [] }
      : url.endsWith('/corpus') ? [] : {}
    return Response.json(data)
  }
  const node = () => markRaw({ children: [] as any[], parent: null as any, style: {}, scrollTo() {} })
  const renderer = createRenderer<any, any>({
    createElement: node, createText: node, createComment: node,
    insert(child, parent) { child.parent = parent; parent.children.push(child) },
    remove() {}, setText() {}, setElementText() {}, patchProp() {},
    parentNode: n => n.parent, nextSibling: () => null,
  })
  const app = renderer.createApp(App)
  app.config.warnHandler = () => {}
  const oldWindow = globalThis.window
  let timerId = 0
  const timers = new Map<number, { callback: () => void; delay: number }>()
  globalThis.window = {
    setTimeout(callback: () => void, delay: number) { timers.set(++timerId, { callback, delay }); return timerId },
    clearTimeout(id: number) { timers.delete(id) },
  } as any
  const flush = () => new Promise<void>(resolve => setImmediate(resolve))
  const reveal = () => {
    const entry = [...timers].find(([, timer]) => timer.delay === 2000)
    assert.ok(entry, '每次发送均启动2000毫秒展示计时器')
    timers.delete(entry[0]); entry[1].callback()
  }
  try {
    const vm = app.mount(node()) as any
    await flush(); await nextTick()
    assert.equal(vm.selectedCase, 'case-0')
    assert.equal(vm.model, 'Sedan'); assert.equal(vm.year, 2023)
    assert.match(vm.serviceStatus, /已配置/)
    assert.equal(calls.filter(c => c.url.endsWith('/meta')).length, 1)
    assert.ok(!calls.some(c => /readiness|rebuild/.test(c.url)))
    const first = vm.send()
    await flush()
    assert.equal(vm.matchingVisible, false)
    assert.deepEqual(vm.traces, resultTraces)
    assert.deepEqual(vm.displayedTraces, [inputTrace])
    assert.equal(vm.selectedResult, undefined, '快速返回结果不能绕过展示延迟')
    reveal(); await first
    assert.deepEqual(vm.displayedTraces, resultTraces)
    assert.equal(timers.size, 0)
    assert.equal(vm.question, '')
    assert.match(vm.messages[1].content, /诊断依据/)
    assert.deepEqual(vm.traces[1], trace)
    assert.equal(vm.nodeLabel(trace.node), '资料匹配')
    assert.equal(vm.nodeLabel('generate'), '诊断分析')
    assert.equal(vm.statusLabel('ask_user'), '请补充信息')
    assert.equal(vm.traceSummary(trace), '已匹配 2 条相关TSB证据。')
    assert.equal(vm.traceSummary(inputTrace), '车辆与问题信息已确认。')
    assert.equal(vm.traceSummary({ ...trace, node: 'evidence_check', detail: 'answer' }), '存在可用于诊断分析的TSB证据。')
    assert.doesNotMatch(vm.traceSummary(trace), /candidateSource|fixed-evidence/)
    await vm.runRetrieval()
    assert.deepEqual(vm.retrievalRows.map((r: any) => [r.order, r.docId, r.title]), [[1, 'TSB-002', '充电系统检查'], [2, 'TSB-001', '接口检查']])
    assert.deepEqual(Object.keys(vm.retrievalRows[0]), ['order', 'docId', 'title'])
    vm.question = '已补齐信息'; status = 'answer'
    const second = vm.send(); await flush(); reveal(); await second
    const sent = calls.filter(c => c.url.endsWith('/diagnosis/stream'))
    assert.equal(JSON.parse(String(sent[1].init!.body)).question, `${request.question}\n补充：已补齐信息`)
    assert.equal(JSON.parse(String(sent[1].init!.body)).scenario, 'normal')
    vm.inspect(vm.messages[1].result)
    vm.question = '不应影响历史的输入'; vm.model = 'SUV'; vm.year = 2024
    await vm.runRetrieval()
    const locked = new URL(calls.at(-1)!.url, 'http://localhost').searchParams
    assert.equal(locked.get('question'), request.question)
    assert.equal(locked.get('model'), 'Sedan'); assert.equal(locked.get('year'), '2023')
    await vm.runEvaluation(); await vm.loadCorpus()
    assert.equal(calls.find(c => c.url.endsWith('/evaluation'))!.init!.method, 'POST')
    failMeta = true; await vm.loadMeta(); assert.equal(vm.serviceStatus, '服务连接失败')
    status = 'error'; vm.question = '再次诊断'
    const failed = vm.send(); await flush(); reveal(); await failed
    assert.match(vm.error, /生成服务暂不可用/)
    status = 'answer'; vm.question = '停止展示等待'
    const waiting = vm.send(); await flush()
    assert.equal(vm.matchingVisible, false)
    vm.stop(); await waiting
    assert.match(vm.messages.at(-1).content, /已停止生成/)
    assert.equal(timers.size, 0, '展示等待被取消后不得残留计时器')
    hold = true; vm.question = '停止请求'; const running = vm.send()
    vm.stop(); await running
    assert.match(vm.messages.at(-1).content, /已停止生成/)
    assert.equal(timers.size, 0)
    assert.ok(!calls.some(c => /readiness|rebuild/.test(c.url)))
  } finally { app.unmount(); globalThis.fetch = previous; globalThis.window = oldWindow }
})
