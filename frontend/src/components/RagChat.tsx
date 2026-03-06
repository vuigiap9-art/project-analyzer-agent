import { useEffect, useMemo, useRef, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { Send, MessageSquare, Loader2, Sparkles, BookOpen, Trash2 } from 'lucide-react'
import MermaidBlock from './MermaidBlock'
import { clearChatMemory, getChatMemory } from '../api'

interface Message {
    role: 'user' | 'ai'
    content: string
    reasoning?: string
    reasonerUsed?: boolean
    sources?: string[]
    timestamp: Date
    streaming?: boolean
}

const QUICK_QUESTIONS = [
    { icon: '🔍', text: '核心逻辑在哪？' },
    { icon: '🛡️', text: '有安全漏洞吗？' },
    { icon: '⚡', text: '帮我分析并发风险' },
    { icon: '📐', text: '项目架构是什么？' },
    { icon: '🧪', text: '代码质量评价如何？' },
]

const API_BASE = '/api'

export default function RagChat({ projectId }: { projectId: string }) {
    const [messages, setMessages] = useState<Message[]>([])
    const [input, setInput] = useState('')
    const [loading, setLoading] = useState(false)
    const [sessionId, setSessionId] = useState('')
    const scrollRef = useRef<HTMLDivElement>(null)
    const bottomRef = useRef<HTMLDivElement>(null)
    const inputRef = useRef<HTMLInputElement>(null)
    const abortRef = useRef<(() => void) | null>(null)
    const stickToBottomRef = useRef(true)
    const pendingAnswerRef = useRef('')
    const pendingThinkingRef = useRef('')
    const flushRafRef = useRef<number | null>(null)

    const flushPendingToMessage = () => {
        const appendAnswer = pendingAnswerRef.current
        const appendThinking = pendingThinkingRef.current
        if (!appendAnswer && !appendThinking) {
            flushRafRef.current = null
            return
        }
        pendingAnswerRef.current = ''
        pendingThinkingRef.current = ''
        setMessages(prev => {
            const updated = [...prev]
            const last = updated[updated.length - 1]
            if (last?.role === 'ai') {
                updated[updated.length - 1] = {
                    ...last,
                    content: last.content + appendAnswer,
                    reasonerUsed: last.reasonerUsed || appendThinking.length > 0,
                    reasoning: (last.reasoning || '') + appendThinking,
                }
            }
            return updated
        })
        flushRafRef.current = null
    }

    const scheduleFlush = () => {
        if (flushRafRef.current != null) return
        flushRafRef.current = window.requestAnimationFrame(flushPendingToMessage)
    }

    const scrollToBottom = (behavior: ScrollBehavior) => {
        bottomRef.current?.scrollIntoView({ behavior })
    }

    const onScroll = () => {
        const el = scrollRef.current
        if (!el) return
        const remaining = el.scrollHeight - el.scrollTop - el.clientHeight
        stickToBottomRef.current = remaining < 120
    }

    // 初始化时不要自动滚动到底（会把“快捷问题”滚走）
    useEffect(() => {
        stickToBottomRef.current = true
    }, [])

    useEffect(() => {
        return () => {
            if (flushRafRef.current != null) {
                window.cancelAnimationFrame(flushRafRef.current)
            }
        }
    }, [])

    useEffect(() => {
        if (!projectId) return
        const key = `rag-session:${projectId}`
        const existing = localStorage.getItem(key)
        if (existing) {
            setSessionId(existing)
            return
        }
        const newSessionId = `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
        localStorage.setItem(key, newSessionId)
        setSessionId(newSessionId)
    }, [projectId])

    useEffect(() => {
        if (!projectId || !sessionId) return
        let cancelled = false
        getChatMemory(projectId, sessionId, 200)
            .then((turns) => {
                if (cancelled || !turns.length) return
                const history: Message[] = []
                for (const turn of turns) {
                    history.push({
                        role: 'user',
                        content: turn.question || '',
                        timestamp: new Date(turn.timestamp || Date.now()),
                        streaming: false,
                    })
                    history.push({
                        role: 'ai',
                        content: turn.answer || '',
                        timestamp: new Date(turn.timestamp || Date.now()),
                        streaming: false,
                    })
                }
                setMessages(history)
            })
            .catch(() => {
            })
        return () => {
            cancelled = true
        }
    }, [projectId, sessionId])

    const sseUrlBase = useMemo(() => {
        return `${API_BASE}/chat/stream?projectId=${encodeURIComponent(projectId)}&sessionId=${encodeURIComponent(sessionId || 'default')}`
    }, [projectId, sessionId])

    const sendMessage = async (question: string) => {
        if (!question.trim() || loading) return
        if (!projectId) {
            setMessages(prev => [...prev, {
                role: 'ai',
                content: '❌ 缺少 projectId：请先分析项目或从“已索引项目”进入。',
                timestamp: new Date(),
                streaming: false,
            }])
            return
        }

        const userMsg: Message = { role: 'user', content: question, timestamp: new Date() }
        setMessages(prev => [...prev, userMsg])
        setInput('')
        setLoading(true)

        // 添加一个空的 AI 消息占位符（流式输出用）
        setMessages(prev => [...prev, {
            role: 'ai',
            content: '',
            sources: [],
            timestamp: new Date(),
            streaming: true,
        }])

        let stopped = false
        const url = `${sseUrlBase}&question=${encodeURIComponent(question)}`

        try {
            // 使用 fetch + ReadableStream 读取 SSE（EventSource 不支持自定义错误处理）
            const response = await fetch(url)
            if (!response.ok) throw new Error(`HTTP ${response.status}`)
            const reader = response.body!.getReader()
            const decoder = new TextDecoder()

            abortRef.current = () => {
                stopped = true
                reader.cancel()
            }

            let buffer = ''
            let currentSources: string[] = []

            while (!stopped) {
                const { value, done } = await reader.read()
                if (done) break

                buffer += decoder.decode(value, { stream: true })

                // 解析完整的 SSE block（以空行分隔，兼容 \n\n 与 \r\n\r\n）
                while (true) {
                    const idxLF = buffer.indexOf('\n\n')
                    const idxCRLF = buffer.indexOf('\r\n\r\n')
                    const idx = idxLF === -1 ? idxCRLF : (idxCRLF === -1 ? idxLF : Math.min(idxLF, idxCRLF))
                    if (idx === -1) break

                    const sepLen = buffer.startsWith('\r\n\r\n', idx) ? 4 : 2
                    const block = buffer.slice(0, idx)
                    buffer = buffer.slice(idx + sepLen)

                    if (!block.trim()) continue
                    const blockLines = block.split(/\r?\n/)
                    let eventName = ''
                    const dataLines: string[] = []
                    for (const bl of blockLines) {
                        if (bl.startsWith('event:')) eventName = bl.slice(6).trim()
                        else if (bl.startsWith('data:')) dataLines.push(bl.startsWith('data: ') ? bl.slice(6) : bl.slice(5))
                    }
                    const eventData = dataLines.join('\n')

                    if (eventName === 'sources') {
                        try { currentSources = JSON.parse(eventData) } catch { /* ignore */ }
                        setMessages(prev => {
                            const updated = [...prev]
                            const last = updated[updated.length - 1]
                            if (last.role === 'ai') updated[updated.length - 1] = { ...last, sources: currentSources }
                            return updated
                        })
                    } else if (eventName === 'token') {
                        pendingAnswerRef.current += eventData
                        scheduleFlush()
                    } else if (eventName === 'thinking_token') {
                        pendingThinkingRef.current += eventData
                        scheduleFlush()
                    } else if (eventName === 'thinking_done') {
                        try {
                            const payload = JSON.parse(eventData) as { reasoning?: string; reasonerUsed?: boolean }
                            setMessages(prev => {
                                const updated = [...prev]
                                const last = updated[updated.length - 1]
                                if (last.role === 'ai') {
                                    updated[updated.length - 1] = {
                                        ...last,
                                        reasonerUsed: payload.reasonerUsed || false,
                                        reasoning: payload.reasoning || last.reasoning || '',
                                    }
                                }
                                return updated
                            })
                        } catch {
                        }
                    } else if (eventName === 'done') {
                        flushPendingToMessage()
                        setMessages(prev => {
                            const updated = [...prev]
                            const last = updated[updated.length - 1]
                            if (last.role === 'ai') updated[updated.length - 1] = { ...last, streaming: false }
                            return updated
                        })
                        stopped = true
                        // 输出结束后再拉回到底部（避免流式过程中打断阅读）
                        scrollToBottom('smooth')
                    } else if (eventName === 'error') {
                        throw new Error(eventData)
                    }
                } // end while parse blocks
            }
        } catch (err: any) {
            if (!stopped) {
                setMessages(prev => {
                    const updated = [...prev]
                    const last = updated[updated.length - 1]
                    if (last.role === 'ai' && last.streaming) {
                        updated[updated.length - 1] = {
                            ...last,
                            content: `❌ 对话出错: ${err.message}`,
                            streaming: false,
                        }
                    }
                    return updated
                })
            }
        } finally {
            setLoading(false)
            abortRef.current = null
            inputRef.current?.focus()
        }
    }

    const handleSubmit = (e: React.FormEvent) => {
        e.preventDefault()
        sendMessage(input)
    }

    const handleClearHistory = async () => {
        if (!projectId || !sessionId) return
        if (!window.confirm('确认清空当前会话历史？')) return
        try {
            await clearChatMemory(projectId, sessionId)
            setMessages([])
        } catch (err: any) {
            setMessages((prev) => [...prev, {
                role: 'ai',
                content: `❌ 清空历史失败: ${err?.message || '未知错误'}`,
                timestamp: new Date(),
            }])
        }
    }

    return (
        <div className="flex flex-col h-full">
            {/* 面板头 */}
            <div className="flex items-center justify-between px-5 py-3 border-b border-dark-800/80 bg-dark-900/50 flex-shrink-0">
                <div className="flex items-center gap-2">
                    <MessageSquare className="w-4 h-4 text-cyber-500" />
                    <span className="text-sm font-semibold text-gray-200">RAG 智能问答</span>
                </div>
                <div className="flex items-center gap-1.5 text-[11px] text-dark-400 font-mono">
                    <Sparkles className="w-3.5 h-3.5" />
                    <span>Top-5 检索增强 · 流式输出</span>
                </div>
                <button
                    type="button"
                    onClick={handleClearHistory}
                    className="ml-3 px-2 py-1 text-[10px] font-mono rounded border border-red-500/40 text-red-300 hover:bg-red-500/10"
                    title="清空当前会话历史"
                >
                    <span className="inline-flex items-center gap-1"><Trash2 className="w-3 h-3" />清空</span>
                </button>
            </div>

            {/* 消息区 */}
            <div
                ref={scrollRef}
                onScroll={onScroll}
                className="flex-1 overflow-y-auto px-4 py-4 space-y-4 stable-scroll"
            >
                {messages.length === 0 && (
                    <div className="flex flex-col items-center justify-center h-full text-center animate-fade-in">
                        <div className="w-16 h-16 rounded-2xl bg-dark-800/60 border border-dark-700/40 flex items-center justify-center mb-5">
                            <BookOpen className="w-8 h-8 text-cyber-500/60" />
                        </div>
                        <p className="text-sm text-dark-400 mb-1">基于审计报告和代码的 RAG 对话</p>
                        <p className="text-xs text-dark-500 mb-6">选择下方快捷问题或输入自定义问题</p>
                        <div className="flex flex-wrap justify-center gap-2 max-w-md">
                            {QUICK_QUESTIONS.map((q) => (
                                <button
                                    key={q.text}
                                    onClick={() => sendMessage(q.text)}
                                    className="tag-chip"
                                >
                                    <span>{q.icon}</span>
                                    {q.text}
                                </button>
                            ))}
                        </div>
                    </div>
                )}

                {messages.map((msg, i) => (
                    <div
                        key={i}
                        className={`flex ${msg.role === 'user' ? 'justify-end' : 'justify-start'} animate-slide-up`}
                    >
                        <div className={`max-w-[85%] ${msg.role === 'user' ? 'chat-bubble-user' : 'chat-bubble-ai'}`}>
                            {msg.role === 'ai' ? (
                                <div className="markdown-body">
                                    <ReactMarkdown
                                        remarkPlugins={[remarkGfm]}
                                        components={{
                                            code({ className, children, ...props }) {
                                                const match = /language-(\w+)/.exec(className || '')
                                                const lang = match?.[1]
                                                const codeStr = String(children).replace(/\n$/, '')

                                                if (lang === 'mermaid') {
                                                    return <MermaidBlock code={codeStr} />
                                                }

                                                if (lang) {
                                                    return (
                                                        <div className="relative group">
                                                            <div className="absolute top-2 right-2 text-[10px] text-dark-500 font-mono uppercase opacity-60">
                                                                {lang}
                                                            </div>
                                                            <code className={className} {...props}>
                                                                {children}
                                                            </code>
                                                        </div>
                                                    )
                                                }

                                                return (
                                                    <code className={className} {...props}>
                                                        {children}
                                                    </code>
                                                )
                                            },
                                        }}
                                    >
                                        {msg.content || (msg.streaming ? '▍' : '')}
                                    </ReactMarkdown>

                                    {msg.reasonerUsed && msg.reasoning && msg.reasoning.trim().length > 0 && (
                                        <details className="mt-3 pt-2 border-t border-dark-700/30" open={false}>
                                            <summary className="cursor-pointer text-[10px] text-cyber-400 font-mono uppercase tracking-wider">思考过程</summary>
                                            <div className="mt-2 markdown-body">
                                                <ReactMarkdown remarkPlugins={[remarkGfm]}>
                                                    {msg.reasoning}
                                                </ReactMarkdown>
                                            </div>
                                        </details>
                                    )}

                                    {/* 流式光标 */}
                                    {msg.streaming && msg.content && (
                                        <span className="inline-block w-0.5 h-4 bg-cyber-400 animate-pulse ml-0.5 align-middle" />
                                    )}
                                    {msg.sources && msg.sources.length > 0 && !msg.streaming && (
                                        <div className="mt-3 pt-2 border-t border-dark-700/30">
                                            <p className="text-[10px] text-dark-500 mb-1 uppercase tracking-wider">引用来源</p>
                                            <div className="flex flex-wrap gap-1">
                                                {msg.sources.map((src, j) => (
                                                    <span key={j} className="text-[10px] text-cyber-500/70 font-mono bg-dark-900/50 px-1.5 py-0.5 rounded">
                                                        {src.split('/').pop()}
                                                    </span>
                                                ))}
                                            </div>
                                        </div>
                                    )}
                                </div>
                            ) : (
                                <p className="text-sm">{msg.content}</p>
                            )}
                            <p className="text-[9px] text-dark-500 mt-1.5 text-right font-mono">
                                {msg.timestamp.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}
                            </p>
                        </div>
                    </div>
                ))}

                <div ref={bottomRef} />
            </div>

            {/* 快捷提问（有消息后显示小版本） */}
            {messages.length > 0 && (
                <div className="flex gap-1.5 px-4 pb-2 overflow-x-auto flex-shrink-0">
                    {QUICK_QUESTIONS.slice(0, 3).map((q) => (
                        <button
                            key={q.text}
                            onClick={() => sendMessage(q.text)}
                            disabled={loading}
                            className="tag-chip whitespace-nowrap text-[10px] disabled:opacity-40"
                        >
                            {q.icon} {q.text}
                        </button>
                    ))}
                </div>
            )}

            {/* 输入框 */}
            <form onSubmit={handleSubmit} className="flex-shrink-0 px-4 pb-4">
                <div className="flex items-center gap-2 glass-panel p-2">
                    <input
                        ref={inputRef}
                        type="text"
                        value={input}
                        onChange={(e) => setInput(e.target.value)}
                        placeholder="输入你的问题..."
                        disabled={loading}
                        className="flex-1 bg-transparent border-none outline-none text-sm text-gray-200
                       placeholder:text-dark-500 font-mono py-1.5 px-2 disabled:opacity-50"
                    />
                    <button
                        type="submit"
                        disabled={loading || !input.trim()}
                        className="w-9 h-9 rounded-lg bg-cyber-600 hover:bg-cyber-500 flex items-center justify-center
                       transition-colors duration-200 disabled:opacity-30 disabled:hover:bg-cyber-600"
                    >
                        {loading ? <Loader2 className="w-4 h-4 text-white animate-spin" /> : <Send className="w-4 h-4 text-white" />}
                    </button>
                </div>
            </form>
        </div>
    )
}
