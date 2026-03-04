import { useState, useRef, useEffect } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { Send, MessageSquare, Loader2, Sparkles, BookOpen } from 'lucide-react'

interface Message {
    role: 'user' | 'ai'
    content: string
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

export default function RagChat() {
    const [messages, setMessages] = useState<Message[]>([])
    const [input, setInput] = useState('')
    const [loading, setLoading] = useState(false)
    const bottomRef = useRef<HTMLDivElement>(null)
    const inputRef = useRef<HTMLInputElement>(null)
    const abortRef = useRef<(() => void) | null>(null)

    useEffect(() => {
        bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
    }, [messages])

    const sendMessage = async (question: string) => {
        if (!question.trim() || loading) return

        const userMsg: Message = { role: 'user', content: question, timestamp: new Date() }
        setMessages(prev => [...prev, userMsg])
        setInput('')
        setLoading(true)

        // 添加一个空的 AI 消息占位符（流式输出用）
        const aiMsgId = Date.now()
        setMessages(prev => [...prev, {
            role: 'ai',
            content: '',
            sources: [],
            timestamp: new Date(),
            streaming: true,
        }])

        let stopped = false
        const url = `${API_BASE}/chat/stream?question=${encodeURIComponent(question)}`

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
                const lines = buffer.split('\n')
                buffer = lines.pop() ?? ''  // 保留不完整的行

                for (const line of lines) {
                    if (line.startsWith('event:')) continue  // 跳过 event: 行，下面的 data: 处理

                    // SSE 格式解析："event: xxx\ndata: yyy\n\n"
                    // Spring SseEmitter 输出格式为：event:name\ndata:value\n\n
                }

                // 重新解析完整的 SSE 块
                const fullText = lines.join('\n')
                const eventBlocks = (buffer + fullText).split('\n\n')
                buffer = eventBlocks.pop() ?? ''

                for (const block of eventBlocks) {
                    if (!block.trim()) continue
                    const blockLines = block.split('\n')
                    let eventName = ''
                    let eventData = ''
                    for (const bl of blockLines) {
                        if (bl.startsWith('event:')) eventName = bl.slice(6).trim()
                        else if (bl.startsWith('data:')) eventData = bl.slice(5)
                    }

                    if (eventName === 'sources') {
                        try { currentSources = JSON.parse(eventData) } catch { /* ignore */ }
                        setMessages(prev => {
                            const updated = [...prev]
                            const last = updated[updated.length - 1]
                            if (last.role === 'ai') updated[updated.length - 1] = { ...last, sources: currentSources }
                            return updated
                        })
                    } else if (eventName === 'token') {
                        setMessages(prev => {
                            const updated = [...prev]
                            const last = updated[updated.length - 1]
                            if (last.role === 'ai') updated[updated.length - 1] = { ...last, content: last.content + eventData }
                            return updated
                        })
                    } else if (eventName === 'done') {
                        setMessages(prev => {
                            const updated = [...prev]
                            const last = updated[updated.length - 1]
                            if (last.role === 'ai') updated[updated.length - 1] = { ...last, streaming: false }
                            return updated
                        })
                        stopped = true
                    } else if (eventName === 'error') {
                        throw new Error(eventData)
                    }
                }
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
            </div>

            {/* 消息区 */}
            <div className="flex-1 overflow-y-auto px-4 py-4 space-y-4">
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
                                    <ReactMarkdown remarkPlugins={[remarkGfm]}>
                                        {msg.content || (msg.streaming ? '▍' : '')}
                                    </ReactMarkdown>
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
