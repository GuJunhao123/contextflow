<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, toRaw } from 'vue'
import { diagnose, jsonRequest, retrievalRequest, type Citation, type DiagnosisRequest, type Evaluation, type Meta, type ProvenanceMap, type Result, type Retrieval, type Scenario, type Source, type Trace } from './api'
import SourceDetails from './SourceDetails.vue'

interface ChatMessage { id: number; role: 'user' | 'ai'; content: string; context?: string; result?: Result }
const model = ref('Sedan'), year = ref<number | null>(2023), question = ref('')
const scenarios = ref<Scenario[]>([]), selectedCase = ref(''), provenance = ref<ProvenanceMap>({})
const messages = ref<ChatMessage[]>([]), traces = ref<Trace[]>([]), selectedResult = ref<Result>()
const matchingVisible = ref(true)
const displayedTraces = computed(() => {
  if (matchingVisible.value) return traces.value
  const matchingIndex = traces.value.findIndex(trace => trace.node === 'lucene_search')
  return matchingIndex < 0 ? traces.value : traces.value.slice(0, matchingIndex)
})
const meta = ref<Meta>(), corpus = ref<Source[]>([]), source = ref<Source>(), evaluation = ref<Evaluation>()
const retrieval = ref<Retrieval>(), retrievalContext = ref<DiagnosisRequest>()
const activeTab = ref('trace'), corpusFilter = ref(''), error = ref(''), busy = ref(false)
const pending = ref<Record<string, boolean>>({}), errors = ref<Record<string, string>>({})
const scrollArea = ref<HTMLElement>(), sequence = ref(0)
const lastRequest = ref<DiagnosisRequest>(), inspectedRequest = ref<DiagnosisRequest>()
const resultRequests = new WeakMap<Result, DiagnosisRequest>()
let controller: AbortController | undefined
let sourceSequence = 0
const visibleCorpus = computed(() => corpus.value.filter(item => `${item.docId} ${item.title} ${item.model} ${item.text}`.toLowerCase().includes(corpusFilter.value.toLowerCase())))
const serviceStatus = computed(() => errors.value.meta ? '服务连接失败' : !meta.value ? '正在连接服务' : meta.value.mode === 'llm' ? '服务已连接 · 生成服务已配置' : '服务已连接 · 生成服务暂不可用')
const retrievalRows = computed(() => retrieval.value?.top5.map((item, index) => ({
  order: index + 1, docId: item.chunkId,
  title: provenance.value[item.chunkId]?.title ?? corpus.value.find(source => source.docId === item.chunkId)?.title ?? 'TSB诊断资料',
})) ?? [])
const statusLabel = (status: string) => ({ answer: '诊断建议已生成', answered: '诊断建议已生成', ask_user: '请补充信息', no_evidence: '暂无匹配资料', error: '处理失败', ok: '已完成', rejected: '核验未通过', running: '处理中' }[status] ?? '状态已更新')
const nodeLabel = (node: string) => ({ input_check: '需求确认', lucene_search: '资料匹配', evidence_check: '证据审查', generate: '诊断分析', citation: '引用核验', tool_validation: '资料核验', error: '异常反馈' }[node] ?? '诊断处理')
function traceSummary(trace: Trace) {
  if (trace.status === 'error') return '本环节未完成，请查看诊断反馈后重试。'
  if (trace.status === 'rejected') return '资料核验未通过。'
  if (trace.node === 'input_check') return trace.detail === 'answer' ? '车辆与问题信息已确认。' : statusLabel(trace.detail)
  if (trace.node === 'evidence_check') return trace.detail === 'answer' ? '存在可用于诊断分析的TSB证据。' : statusLabel(trace.detail)
  if (trace.node === 'lucene_search') {
    const count = /(?:有序证据|证据)=(\d+)/.exec(trace.detail)?.[1]
    return count !== undefined ? `已匹配 ${count} 条相关TSB证据。` : '资料匹配已完成。'
  }
  if (trace.node === 'generate') return '已依据证据生成诊断依据、排查步骤与建议结论，请在回答中查看。'
  if (trace.node === 'citation') return '引用定位核验已完成；诊断仍需专业人员结合实车核实。'
  return '已收到诊断进程更新。'
}
async function task(key: string, action: () => Promise<void>) {
  if (pending.value[key]) return
  pending.value[key] = true; errors.value[key] = ''
  try { await action() } catch (e) { errors.value[key] = e instanceof Error ? e.message : String(e) }
  finally { pending.value[key] = false }
}
function loadMeta() { return task('meta', async () => { meta.value = await jsonRequest<Meta>('/meta') }) }
function loadProvenance() { return task('provenance', async () => { provenance.value = await jsonRequest<ProvenanceMap>('/provenance') }) }
function loadScenarios() { return task('scenarios', async () => {
  scenarios.value = await jsonRequest<Scenario[]>('/scenarios')
  const preferred = scenarios.value.find(item => item.model === 'Sedan' && item.year === 2023) ?? scenarios.value[0]
  if (preferred && !question.value && !messages.value.length) fillScenario(preferred)
}) }
function loadCorpus() { return task('corpus', async () => { corpus.value = await jsonRequest<Source[]>('/corpus'); await loadProvenance() }) }
function openSource(citation: Citation) {
  const version = ++sourceSequence
  source.value = undefined
  return task('source', async () => {
    const data = await jsonRequest<Source>(`/source/${encodeURIComponent(citation.docId)}`)
    if (version === sourceSequence) source.value = data
  })
}
function inspect(result: Result) {
  selectedResult.value = result; traces.value = result.trace; source.value = undefined; sourceSequence++
  inspectedRequest.value = resultRequests.get(toRaw(result)); retrieval.value = undefined; activeTab.value = 'evidence'
}
function fillScenario(item: Scenario) {
  if (busy.value) return
  model.value = item.model; year.value = item.year; question.value = item.question; selectedCase.value = item.id
  inspectedRequest.value = undefined; retrieval.value = undefined
}
function clearChat() {
  messages.value = []; selectedResult.value = undefined; traces.value = []; source.value = undefined; sourceSequence++
  lastRequest.value = undefined; inspectedRequest.value = undefined; retrieval.value = undefined; error.value = ''
}
async function scrollBottom() { await nextTick(); scrollArea.value?.scrollTo({ top: scrollArea.value.scrollHeight, behavior: 'smooth' }) }
function stop() { controller?.abort() }
async function send() {
  if (busy.value || !question.value.trim()) return
  if (year.value !== null && (!Number.isInteger(year.value) || year.value < 1900 || year.value > 2100)) { error.value = '请输入 1900–2100 范围内的整数年份'; return }
  const text = question.value.trim()
  const followupRequest = selectedResult.value?.status === 'ask_user' ? resultRequests.get(toRaw(selectedResult.value)) : undefined
  const requestQuestion = followupRequest ? `${followupRequest.question}\n补充：${text}`.slice(0, 2000) : text
  const request = { model: model.value.trim() || null, year: year.value, question: requestQuestion, scenario: 'normal' }
  lastRequest.value = request; inspectedRequest.value = undefined
  messages.value.push({ id: ++sequence.value, role: 'user', content: requestQuestion, context: `${model.value || '未填车型'} · ${year.value ?? '未填年份'}` })
  const replyId = ++sequence.value
  messages.value.push({ id: replyId, role: 'ai', content: '正在分析车辆问题，请在诊断进程中查看进度…' })
  question.value = ''; error.value = ''; traces.value = []; selectedResult.value = undefined; source.value = undefined; sourceSequence++
  retrieval.value = undefined; activeTab.value = 'trace'; busy.value = true
  const run = new AbortController(); controller = run
  matchingVisible.value = false
  let revealTimer: ReturnType<typeof window.setTimeout>
  let cancelReveal: () => void
  const presentationReady = new Promise<void>((resolve, reject) => {
    cancelReveal = () => { window.clearTimeout(revealTimer); reject(new DOMException('已停止', 'AbortError')) }
    revealTimer = window.setTimeout(() => {
      matchingVisible.value = true
      run.signal.removeEventListener('abort', cancelReveal)
      resolve()
    }, 2000)
    run.signal.addEventListener('abort', cancelReveal, { once: true })
  })
  void presentationReady.catch(() => {})
  const timer = window.setTimeout(() => run.abort(new Error('诊断等待超时，请稍后重试。')), Math.max(30000, (meta.value?.limits.timeoutMs ?? 120000) + 10000))
  const reply = () => messages.value.find(m => m.id === replyId)!
  void scrollBottom()
  try {
    const result = await diagnose(request, run, trace => { traces.value.push(trace) })
    await presentationReady
    if (run.signal.aborted) throw new DOMException('已停止', 'AbortError')
    resultRequests.set(result, request)
    selectedResult.value = result; traces.value = result.trace
    reply().content = result.answer || result.message || '诊断结束，未返回回答。'; reply().result = result
    if (result.status === 'error') error.value = result.message
  } catch (e) {
    const reason = run.signal.aborted ? (run.signal.reason instanceof Error && run.signal.reason.name !== 'AbortError' ? run.signal.reason.message : '已停止生成；当前回答不完整。') : (e instanceof Error ? e.message : String(e))
    error.value = reason; reply().content = reason
  } finally {
    window.clearTimeout(timer); window.clearTimeout(revealTimer!)
    run.signal.removeEventListener('abort', cancelReveal!)
    matchingVisible.value = true; busy.value = false; controller = undefined; void scrollBottom()
  }
}
function enter(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) { event.preventDefault(); void send() }
}
function runRetrieval() {
  return task('retrieval', async () => {
    const request = retrievalRequest({ model: model.value.trim() || null, year: year.value, question: question.value.trim(), scenario: 'normal' }, lastRequest.value, inspectedRequest.value)
    const query = new URLSearchParams({ model: request.model!, year: String(request.year), question: request.question })
    retrieval.value = undefined
    const data = await jsonRequest<Retrieval>(`/retrieval?${query}`)
    retrieval.value = data; retrievalContext.value = request
  })
}
function runEvaluation() { return task('evaluation', async () => { evaluation.value = await jsonRequest<Evaluation>('/evaluation', { method: 'POST' }) }) }
onMounted(() => { void loadMeta(); void loadScenarios(); void loadProvenance() })
onBeforeUnmount(stop)
</script>

<template>
  <div class="app-shell">
    <header class="header-bar">
      <div class="brand"><span class="brand-mark">CF</span><div><h1>ContextFlow</h1><span class="subtitle">TSB诊断工作台</span></div><a-tag color="blue">{{ serviceStatus }}</a-tag></div>
      <div class="toolbar"><a-button :loading="pending.meta" @click="loadMeta">刷新连接状态</a-button><a-button :disabled="busy || pending.retrieval" @click="clearChat">清空对话</a-button></div>
    </header>
    <div class="safety-banner"><strong>安全提示</strong>诊断建议不代替专业检修；涉及高压、制动、过热或行车安全，请安全停用并联系专业人员，勿自行拆修带电部件。</div>
    <a-alert v-for="key in ['meta', 'scenarios', 'provenance']" v-show="errors[key]" :key="key" type="error" show-icon :message="errors[key]" class="global-error" />
    <main class="main-content">
      <section class="chat-section" aria-label="诊断对话">
        <div class="section-heading"><h2>诊断对话</h2><span>诊断依据 · 排查步骤 · 建议结论</span></div>
        <div class="case-selector"><div class="row"><strong>常见问题</strong><span class="hint">选择问题，核对车型与年份后发送</span><a-button size="small" :loading="pending.scenarios" @click="loadScenarios">刷新问题</a-button></div><div class="case-buttons"><a-button v-for="item in scenarios" :key="item.id" size="small" :type="selectedCase === item.id ? 'primary' : 'default'" :disabled="busy || pending.retrieval" @click="fillScenario(item)">{{ item.label }}</a-button></div></div>
        <div ref="scrollArea" class="messages-container" aria-live="polite">
          <div v-if="!messages.length" class="welcome"><div class="welcome-mark">CF</div><h2>让诊断建议有据可查</h2><p>选择常见问题或描述车辆症状。<br>跟踪诊断进程，查看回答中的诊断依据、排查步骤与建议结论。</p></div>
          <article v-for="item in messages" :key="item.id" class="message-item" :class="item.role">
            <a-avatar :style="{ background: item.role === 'user' ? '#1677ff' : '#e8edf5', color: item.role === 'user' ? '#fff' : '#536176' }">{{ item.role === 'user' ? '我' : 'CF' }}</a-avatar>
            <div class="message-body"><div v-if="item.context" class="message-context">{{ item.context }}</div><div class="bubble">{{ item.content }}</div><div v-if="item.result" class="message-foot"><a-tag>{{ statusLabel(item.result.status) }}</a-tag><a-button type="link" size="small" :disabled="busy || pending.retrieval" @click="inspect(item.result)">查看本次证据（{{ item.result.citations.length }}）</a-button></div></div>
          </article>
        </div>
        <div class="composer">
          <a-alert v-if="error" type="warning" :message="error" closable @close="error = ''" />
          <div class="vehicle-fields"><label>车型<a-input v-model:value="model" placeholder="如 Sedan" :maxlength="64" :disabled="busy" /></label><label>年份<a-input-number v-model:value="year" :min="1900" :max="2100" :precision="0" placeholder="2023" :disabled="busy" /></label></div>
          <a-textarea v-model:value="question" aria-label="诊断问题或补充信息" placeholder="描述症状或补充信息；Enter 发送，Shift + Enter 换行" :auto-size="{ minRows: 3, maxRows: 6 }" :maxlength="2000" :disabled="busy" @keydown="enter" />
          <div class="composer-actions"><span>{{ question.length }} / 2000 · 请核对车辆信息</span><a-button v-if="busy" danger @click="stop">停止生成</a-button><a-button v-else type="primary" :disabled="!question.trim() || pending.retrieval" @click="send">发送问题</a-button></div>
        </div>
      </section>
      <aside class="workspace" aria-label="TSB证据工作台">
        <div class="section-heading"><h2>TSB证据工作台</h2><span>资料定位与诊断核查</span></div>
        <a-tabs v-model:activeKey="activeTab" class="workspace-tabs">
          <a-tab-pane key="trace" tab="诊断进程"><p class="hint">{{ busy ? '正在接收诊断进度…' : '展示本次诊断已返回的处理环节与用时。' }} 诊断依据与排查建议请查看模型回答。</p><a-empty v-if="!displayedTraces.length" :description="busy ? '正在确认诊断需求…' : '发送问题后显示诊断进程'" /><div v-for="(trace, index) in displayedTraces" :key="index" class="trace-card"><div class="row"><strong>{{ index + 1 }} · {{ nodeLabel(trace.node) }}</strong><a-tag :color="trace.status === 'error' ? 'red' : 'blue'">{{ statusLabel(trace.status) }}</a-tag><span>{{ trace.durationMs }} 毫秒</span></div><p>{{ traceSummary(trace) }}</p></div><p v-if="selectedResult" class="hint">{{ statusLabel(selectedResult.status) }}</p></a-tab-pane>
          <a-tab-pane key="evidence" tab="引用原文">
            <a-empty v-if="!selectedResult?.citations.length" description="完成诊断或选择历史回答后查看引用" />
            <article v-for="citation in selectedResult?.citations" :key="citation.docId + citation.page" class="evidence-card"><strong>{{ citation.title }}</strong><SourceDetails :doc-id="citation.docId" :provenance="provenance[citation.docId]" /><p class="plain-text">{{ citation.text }}</p><a-button size="small" :loading="pending.source" @click="openSource(citation)">查看TSB正文</a-button></article>
            <a-alert v-if="errors.source" type="error" :message="errors.source" /><div v-if="source" class="source-panel"><h3>{{ source.title }}</h3><SourceDetails :doc-id="source.docId" :provenance="provenance[source.docId]" /><p class="plain-text">{{ source.text }}</p><h4>检修建议</h4><p class="plain-text">{{ source.advice }}</p></div>
          </a-tab-pane>
          <a-tab-pane key="retrieval" tab="相关TSB">
            <p class="hint">{{ inspectedRequest ? '已锁定历史回答对应的问题，不受输入框修改影响。' : '按当前问题查看资料；输入为空时沿用上次诊断问题。' }} 证据按资料顺序列出，序号不代表相关性评分。</p>
            <div class="row"><a-button :disabled="busy" :loading="pending.retrieval" @click="runRetrieval">查看相关TSB</a-button><a-button v-if="inspectedRequest" :disabled="pending.retrieval" @click="inspectedRequest = undefined; retrieval = undefined">改用当前输入</a-button></div>
            <a-alert v-if="errors.retrieval" type="error" :message="errors.retrieval" />
            <template v-if="retrieval"><p class="plain-text">{{ retrievalContext?.model }} · {{ retrievalContext?.year }} · {{ retrievalContext?.question }}</p><div class="retrieval-table"><table><thead><tr><th>顺序</th><th>TSB编号</th><th>标题</th></tr></thead><tbody><tr v-for="row in retrievalRows" :key="row.docId"><td>{{ row.order }}</td><td>{{ row.docId }}</td><td>{{ row.title }}</td></tr></tbody></table></div><a-empty v-if="!retrievalRows.length" description="未匹配到适用TSB，请核对车辆信息与问题" /></template>
          </a-tab-pane>
          <a-tab-pane key="service" tab="服务状态"><h3>连接与配置</h3><p>{{ serviceStatus }}</p><p class="hint">连接状态来自最近一次服务信息请求，不代表已完成诊断调用。生成是否成功以本次诊断反馈为准。</p><a-button :loading="pending.meta" @click="loadMeta">刷新连接状态</a-button><a-alert v-if="errors.meta" type="error" :message="errors.meta" /><p v-if="meta">TSB资料：{{ meta.corpusSize }} 条</p></a-tab-pane>
          <a-tab-pane key="evaluation" tab="案例校验"><p class="hint">校验案例的前五条证据匹配情况，不评估诊断正确率、模型能力或其他车型表现。</p><a-button :loading="pending.evaluation" @click="runEvaluation">运行案例校验</a-button><a-alert v-if="errors.evaluation" type="error" :message="errors.evaluation" /><template v-if="evaluation"><div class="metric-grid"><a-statistic title="前五证据命中率" :value="evaluation.hitAt5" :precision="3" /><a-statistic title="前五证据召回率" :value="evaluation.recallAt5" :precision="3" /><a-statistic title="校验案例数" :value="evaluation.total" /></div><p class="hint">指标按本次返回案例统计；无证据案例以空证据是否符合预期判断。</p><article v-for="item in evaluation.cases" :key="item.id" class="evidence-card"><strong>{{ item.question }}</strong><p>预期证据：{{ item.gold.join('、') || '无匹配证据' }}<br>返回证据：{{ item.actual.join('、') || '无匹配证据' }}</p><a-tag :color="item.hit ? 'green' : 'orange'">{{ item.hit ? '证据符合预期' : '证据未命中' }}</a-tag><span>证据召回率：{{ item.recall }}</span></article></template></a-tab-pane>
          <a-tab-pane key="corpus" tab="TSB资料库"><a-button :loading="pending.corpus" @click="loadCorpus">加载TSB资料</a-button><p class="hint">查阅技术通报及检修建议，使用前请核对章节、车型年份和实际车辆状态。</p><a-alert v-if="errors.corpus" type="error" :message="errors.corpus" /><a-input v-model:value="corpusFilter" placeholder="筛选TSB编号、车型或正文" allow-clear /><p class="hint">已加载 {{ corpus.length }} 条 · 匹配 {{ visibleCorpus.length }} 条</p><article v-for="item in visibleCorpus" :key="item.docId" class="evidence-card"><strong>{{ item.title }}</strong><SourceDetails :doc-id="item.docId" :provenance="provenance[item.docId]" /><p class="hint">{{ item.model }} · {{ item.yearFrom }}–{{ item.yearTo }}</p><p class="plain-text">{{ item.text }}</p><details><summary>检修建议</summary><p class="plain-text">{{ item.advice }}</p></details></article></a-tab-pane>
        </a-tabs>
      </aside>
    </main>
  </div>
</template>
