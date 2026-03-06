import { createRootRoute, Link, Outlet } from '@tanstack/react-router'
import { LayoutDashboard, FileArchive, MessageSquareCode } from 'lucide-react'
import { cn } from '@/lib/utils'
import { useStore } from '@/store/useStore'

export const Route = createRootRoute({
    component: () => <RootLayout />
})

function RootLayout() {
    const isChatOpen = useStore((state) => state.isChatOpen)

    return (
        <div className="flex h-screen w-full bg-background overflow-hidden text-foreground selection:bg-primary selection:text-primary-foreground font-sans">
            {/* 极简侧边导航栏 (Sleek Sidebar) */}
            <nav className="w-16 sm:w-20 border-r border-border/40 bg-card/30 backdrop-blur-md flex flex-col items-center py-6 gap-8 z-10 shrink-0">
                <Link to="/" className="w-10 h-10 rounded-xl bg-gradient-to-tr from-blue-600 to-indigo-500 shadow-lg shadow-blue-500/20 flex items-center justify-center mb-4 transition-transform hover:scale-105">
                    <span className="font-bold text-white text-lg">IA</span>
                </Link>

                <div className="flex flex-col gap-4 w-full px-2">
                    <NavItem to="/" icon={<LayoutDashboard size={22} />} title="Dashboard" />
                    <NavItem to="/analyzer" icon={<FileArchive size={22} />} title="Analyzer" />
                    <NavItem to="/snippet" icon={<MessageSquareCode size={22} />} title="Snippet Tool" />
                </div>
            </nav>

            {/* 主视图区域 (Main View Area) */}
            <main className="flex-1 relative flex flex-col min-w-0 transition-all duration-300">
                <Outlet />
            </main>

            {/* AI Assistant 挂载点占位 (将在后续实现具体抽屉侧栏) */}
            {isChatOpen && (
                <div className="w-[400px] border-l border-border/40 bg-card/50 backdrop-blur-xl shrink-0 absolute right-0 top-0 bottom-0 shadow-2xl z-50">
                    {/* Chat Component goes here */}
                </div>
            )}
        </div>
    )
}

function NavItem({ to, icon, title }: { to: string, icon: React.ReactNode, title: string }) {
    return (
        <Link
            to={to}
            activeProps={{ className: "bg-primary/10 text-primary" }}
            className="group relative flex h-12 w-full items-center justify-center rounded-xl text-muted-foreground hover:bg-muted/50 hover:text-foreground transition-all"
        >
            {icon}

            {/* Tooltip on Hover */}
            <span className="absolute left-full ml-4 opacity-0 -translate-x-2 group-hover:opacity-100 group-hover:translate-x-0 transition-all pointer-events-none rounded-md bg-popover px-2 py-1 text-sm text-popover-foreground shadow-md whitespace-nowrap z-50 border">
                {title}
            </span>
        </Link>
    )
}
