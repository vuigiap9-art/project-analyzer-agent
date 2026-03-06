export interface AnalyzeResponse {
    status: string
    mode?: 'standard' | 'interactive'
    projectId?: string
    alreadyIndexed?: boolean
    filesScanned?: number
    auditReport?: string
    blueprint?: string
    reasoning?: string
    reasonerUsed?: boolean
    message?: string
}

export interface AnalyzeStreamStats {
    calls: number
    maxCalls: number
    visitedFiles: number
    maxVisitedFiles: number
    totalReadLines: number
    maxTotalReadLines: number
    remainingCalls: number
    remainingVisitedFiles: number
    remainingLines: number
}

export interface AuditTuningOptions {
    maxCalls?: number
    maxTotalReadLines?: number
    maxLinesPerCall?: number
    targetLinesPerCall?: number
    minLinesPerCall?: number
    maxVisitedFiles?: number
}

export interface ChatResponse {
    answer: string
    reasoning?: string
    reasonerUsed?: boolean
    sources: string[]
}

export interface ChatMemoryTurn {
    sessionId: string
    turn: number
    timestamp: string
    question: string
    answer: string
}

export interface BrowseEntry {
    name: string
    path: string
    type: 'dir' | 'file'
}

export interface BrowseResult {
    current: string
    parent: string | null
    entries: BrowseEntry[]
}

export interface SnippetAnalyzeRequest {
    path: string
    language: string
    content: string
}

export interface SnippetAnalyzeResponse {
    status: string
    file: string
    language: string
    analysis: string
    message?: string
}

export interface ProjectMeta {
    projectId: string
    rootPath: string
    indexedAt: string
    filesScanned: number
}

export interface ProjectDetail {
    projectId: string
    meta: ProjectMeta
    blueprint: string
}

export interface HealthResponse {
    status: string
    service: string
}

const API_BASE = '/api'

export async function analyzeProject(path: string, opts?: { force?: boolean }): Promise<AnalyzeResponse> {
    const force = opts?.force ? 'true' : 'false'
    const res = await fetch(`${API_BASE}/analyze?path=${encodeURIComponent(path)}&force=${force}`)
    if (!res.ok) {
        const errorData = await res.json().catch(() => ({}))
        throw new Error(errorData.message || `分析失败 (HTTP ${res.status})`)
    }
    return res.json()
}

export async function analyzeProjectInteractive(path: string, tuning?: AuditTuningOptions): Promise<AnalyzeResponse> {
    const params = new URLSearchParams({ path })
    appendAuditTuningParams(params, tuning)
    const res = await fetch(`${API_BASE}/analyze/interactive?${params.toString()}`)
    const data = await res.json().catch(() => ({}))
    if (!res.ok) {
        throw new Error(data.message || `交互式分析失败 (HTTP ${res.status})`)
    }
    return data
}

export async function analyzeProjectStream(
    path: string,
    mode: 'standard' | 'interactive',
    onProgress: (percent: number, text: string) => void,
    onLog?: (message: string) => void,
    onStats?: (stats: AnalyzeStreamStats) => void,
    tuning?: AuditTuningOptions,
): Promise<AnalyzeResponse> {
    const params = new URLSearchParams({ path, mode })
    appendAuditTuningParams(params, tuning)
    const url = `${API_BASE}/analyze/stream?${params.toString()}`
    const res = await fetch(url)
    if (!res.ok) {
        const data = await res.json().catch(() => ({}))
        throw new Error(data.message || `流式分析失败 (HTTP ${res.status})`)
    }
    if (!res.body) throw new Error('流式响应体为空')

    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    let finalResponse: AnalyzeResponse | null = null

    while (true) {
        const { value, done } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })

        while (true) {
            const idxLF = buffer.indexOf('\n\n')
            const idxCRLF = buffer.indexOf('\r\n\r\n')
            const idx = idxLF === -1 ? idxCRLF : (idxCRLF === -1 ? idxLF : Math.min(idxLF, idxCRLF))
            if (idx === -1) break

            const sepLen = buffer.startsWith('\r\n\r\n', idx) ? 4 : 2
            const block = buffer.slice(0, idx)
            buffer = buffer.slice(idx + sepLen)
            if (!block.trim()) continue

            const lines = block.split(/\r?\n/)
            let eventName = ''
            const dataLines: string[] = []
            for (const line of lines) {
                if (line.startsWith('event:')) eventName = line.slice(6).trim()
                else if (line.startsWith('data:')) dataLines.push(line.startsWith('data: ') ? line.slice(6) : line.slice(5))
            }
            const eventData = dataLines.join('\n')

            if (eventName === 'progress') {
                const payload = JSON.parse(eventData) as { percent: number; text: string }
                onProgress(payload.percent, payload.text)
            } else if (eventName === 'log') {
                onLog?.(eventData)
            } else if (eventName === 'stats') {
                const payload = JSON.parse(eventData) as AnalyzeStreamStats
                onStats?.(payload)
            } else if (eventName === 'done') {
                finalResponse = JSON.parse(eventData) as AnalyzeResponse
            } else if (eventName === 'error') {
                const payload = JSON.parse(eventData) as { message?: string }
                throw new Error(payload.message || '流式分析失败')
            }
        }
    }

    if (!finalResponse) throw new Error('流式分析未返回结果')
    return finalResponse
}

function appendAuditTuningParams(params: URLSearchParams, tuning?: AuditTuningOptions) {
    if (!tuning) return
    if (tuning.maxCalls) params.set('auditMaxCalls', String(tuning.maxCalls))
    if (tuning.maxTotalReadLines) params.set('auditMaxTotalLines', String(tuning.maxTotalReadLines))
    if (tuning.maxLinesPerCall) params.set('auditMaxLinesPerCall', String(tuning.maxLinesPerCall))
    if (tuning.targetLinesPerCall) params.set('auditTargetLinesPerCall', String(tuning.targetLinesPerCall))
    if (tuning.minLinesPerCall) params.set('auditMinLinesPerCall', String(tuning.minLinesPerCall))
    if (tuning.maxVisitedFiles) params.set('auditMaxVisitedFiles', String(tuning.maxVisitedFiles))
}

export async function browsePath(path: string): Promise<BrowseResult> {
    const res = await fetch(`${API_BASE}/browse?path=${encodeURIComponent(path)}`)
    const data = await res.json().catch(() => ({}))
    if (!res.ok) throw new Error(data.error || `浏览目录失败 (HTTP ${res.status})`)
    return data
}

export async function analyzeSnippet(payload: SnippetAnalyzeRequest): Promise<SnippetAnalyzeResponse> {
    const res = await fetch(`${API_BASE}/analyze-snippet`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
    })
    const data = await res.json().catch(() => ({}))
    if (!res.ok) throw new Error(data.message || `片段审查失败 (HTTP ${res.status})`)
    return data
}

export async function chatWithRAG(question: string, projectId: string, sessionId?: string): Promise<ChatResponse> {
    const res = await fetch(`${API_BASE}/chat`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question, projectId, sessionId }),
    })
    if (!res.ok) {
        const errorData = await res.json().catch(() => ({}))
        throw new Error(errorData.message || `对话失败 (HTTP ${res.status})`)
    }
    return res.json()
}

export async function listProjects(): Promise<ProjectMeta[]> {
    const res = await fetch(`${API_BASE}/projects`)
    if (!res.ok) throw new Error('获取已索引项目失败')
    return res.json()
}

export async function getProject(projectId: string): Promise<ProjectDetail> {
    const res = await fetch(`${API_BASE}/projects/${encodeURIComponent(projectId)}`)
    const data = await res.json().catch(() => ({}))
    if (!res.ok) throw new Error(data.error || '加载项目失败')
    return data
}

export async function deleteProject(projectId: string): Promise<void> {
    const res = await fetch(`${API_BASE}/projects/${encodeURIComponent(projectId)}`, { method: 'DELETE' })
    const data = await res.json().catch(() => ({}))
    if (!res.ok) throw new Error(data.message || '删除项目失败')
}

export async function getChatMemory(projectId: string, sessionId?: string, limit = 100): Promise<ChatMemoryTurn[]> {
    const params = new URLSearchParams()
    if (sessionId) params.set('sessionId', sessionId)
    params.set('limit', String(limit))
    const res = await fetch(`${API_BASE}/projects/${encodeURIComponent(projectId)}/chat-memory?${params.toString()}`)
    const data = await res.json().catch(() => ({}))
    if (!res.ok) throw new Error(data.message || '获取对话历史失败')
    return (data.turns || []) as ChatMemoryTurn[]
}

export async function clearChatMemory(projectId: string, sessionId?: string): Promise<number> {
    const params = new URLSearchParams()
    if (sessionId) params.set('sessionId', sessionId)
    const query = params.toString()
    const res = await fetch(`${API_BASE}/projects/${encodeURIComponent(projectId)}/chat-memory${query ? `?${query}` : ''}`, {
        method: 'DELETE',
    })
    const data = await res.json().catch(() => ({}))
    if (!res.ok) throw new Error(data.message || '清空对话历史失败')
    return Number(data.removed || 0)
}

export async function checkHealth(): Promise<HealthResponse> {
    const res = await fetch(`${API_BASE}/health`)
    if (!res.ok) throw new Error('健康检查失败')
    return res.json()
}
