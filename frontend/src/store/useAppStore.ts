import { create } from 'zustand'

type MainView = 'dashboard' | 'insight' | 'snippet'

interface AppState {
    activeProjectId: string
    mainView: MainView
    isChatOpen: boolean
    setActiveProjectId: (projectId: string) => void
    setMainView: (view: MainView) => void
    setChatOpen: (open: boolean) => void
    toggleChat: () => void
}

export const useAppStore = create<AppState>((set) => ({
    activeProjectId: '',
    mainView: 'dashboard',
    isChatOpen: true,
    setActiveProjectId: (activeProjectId) => set({ activeProjectId }),
    setMainView: (mainView) => set({ mainView }),
    setChatOpen: (isChatOpen) => set({ isChatOpen }),
    toggleChat: () => set((state) => ({ isChatOpen: !state.isChatOpen })),
}))
