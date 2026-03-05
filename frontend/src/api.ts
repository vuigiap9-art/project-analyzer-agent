export interface AnalyzeResponse {
    status: string
    projectId?: string
    alreadyIndexed?: boolean
    filesScanned?: number
    auditReport?: string
    blueprint?: string
    message?: string
}

export interface ChatResponse {
    answer: string
    sources: string[]
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

export async function analyzeProjectInteractive(path: string): Promise<AnalyzeResponse> {
    const res = await fetch(`${API_BASE}/analyze/interactive?path=${encodeURIComponent(path)}`)
    const data = await res.json().catch(() => ({}))
    if (!res.ok) {
        throw new Error(data.message || `交互式分析失败 (HTTP ${res.status})`)
    }
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

export async function checkHealth(): Promise<HealthResponse> {
    const res = await fetch(`${API_BASE}/health`)
    if (!res.ok) throw new Error('健康检查失败')
    return res.json()
}
