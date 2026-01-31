import React, { useState } from 'react';
import { User } from '../types';
import { LogOut, Save, Key, User as UserIcon, ChevronLeft } from 'lucide-react';
import { ToastType } from './Toast';

interface UserCenterViewProps {
  user: User;
  onLogout: () => void;
  onUpdateProfile: (newUsername?: string, newPassword?: string) => Promise<boolean>;
  onBack: () => void;
  showToast: (title: string, message: string, type: ToastType) => void;
}

export const UserCenterView: React.FC<UserCenterViewProps> = ({ 
  user, 
  onLogout, 
  onUpdateProfile, 
  onBack,
  showToast 
}) => {
  const [username, setUsername] = useState(user.username);
  const [password, setPassword] = useState('');
  const [isLoading, setIsLoading] = useState(false);

  const checkPasswordComplexity = (pwd: string) => {
    // Complexity: At least 6 characters, must contain letter and number
    const hasLetter = /[a-zA-Z]/.test(pwd);
    const hasNumber = /\d/.test(pwd);
    return pwd.length >= 6 && hasLetter && hasNumber;
  };

  const handleUpdate = async () => {
    if (!username.trim()) {
        showToast("输入错误", "用户名不能为空", 'error');
        return;
    }

    if (password && !checkPasswordComplexity(password)) {
        showToast('密码强度不足', '密码需包含字母和数字，且长度不少于6位', 'error');
        return;
    }
    
    // If no changes
    if (username === user.username && !password) {
        return;
    }

    setIsLoading(true);
    const success = await onUpdateProfile(
        username !== user.username ? username : undefined,
        password || undefined
    );
    setIsLoading(false);
    
    if (success && password) {
        setPassword(''); // Clear password field on success
    }
  };

  return (
    <div className="min-h-screen bg-slate-50">
      <header className="bg-white border-b border-slate-200 px-4 py-3 flex items-center">
        <button 
          onClick={onBack}
          className="p-2 -ml-2 text-slate-600 hover:bg-slate-100 rounded-full transition-colors"
        >
          <ChevronLeft className="w-6 h-6" />
        </button>
        <h1 className="ml-2 font-bold text-slate-800 text-lg">个人中心</h1>
      </header>

      <div className="p-4 max-w-lg mx-auto">
        {/* Profile Header */}
        <div className="bg-white rounded-2xl shadow-sm border border-slate-100 p-6 flex flex-col items-center mb-6">
            <div className="w-20 h-20 bg-blue-100 rounded-full flex items-center justify-center text-blue-600 mb-4">
                <UserIcon className="w-10 h-10" />
            </div>
            <h2 className="text-xl font-bold text-slate-800">{user.username}</h2>
            <span className="inline-block mt-1 px-3 py-1 bg-slate-100 text-slate-500 rounded-full text-xs font-medium uppercase">
                {user.role === 'admin' ? '管理员' : '巡检员'}
            </span>
            <div className="text-xs text-slate-400 mt-2">ID: {user.id}</div>
        </div>

        {/* Edit Form */}
        <div className="bg-white rounded-2xl shadow-sm border border-slate-100 p-6 mb-6">
            <h3 className="font-bold text-slate-800 mb-4">修改资料</h3>
            
            <div className="space-y-4">
                <div>
                    <label className="block text-xs font-medium text-slate-500 mb-1">用户名</label>
                    <div className="relative">
                        <UserIcon className="absolute left-3 top-2.5 w-4 h-4 text-slate-400" />
                        <input 
                            type="text" 
                            value={username}
                            onChange={(e) => setUsername(e.target.value)}
                            className="w-full bg-slate-50 border border-slate-200 rounded-lg py-2 pl-9 pr-4 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
                        />
                    </div>
                </div>

                <div>
                    <div className="flex justify-between items-center mb-1">
                        <label className="block text-xs font-medium text-slate-500">新密码 (留空则不修改)</label>
                        <span className="text-[10px] text-slate-400">需包含字母数字，至少6位</span>
                    </div>
                    <div className="relative">
                        <Key className="absolute left-3 top-2.5 w-4 h-4 text-slate-400" />
                        <input 
                            type="password" 
                            value={password}
                            onChange={(e) => setPassword(e.target.value)}
                            placeholder="********"
                            className="w-full bg-slate-50 border border-slate-200 rounded-lg py-2 pl-9 pr-4 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
                        />
                    </div>
                </div>

                <button 
                    onClick={handleUpdate}
                    disabled={isLoading || (username === user.username && !password)}
                    className="w-full bg-blue-600 text-white rounded-lg py-2.5 font-medium flex items-center justify-center space-x-2 disabled:opacity-50 disabled:cursor-not-allowed hover:bg-blue-700 transition-colors"
                >
                     {isLoading ? (
                        <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin"></div>
                     ) : (
                         <>
                            <Save className="w-4 h-4" />
                            <span>保存修改</span>
                         </>
                     )}
                </button>
            </div>
        </div>

        {/* Logout Button */}
        <button 
            onClick={onLogout}
            className="w-full bg-red-50 text-red-600 border border-red-100 rounded-xl py-3 font-medium flex items-center justify-center space-x-2 hover:bg-red-100 transition-colors"
        >
            <LogOut className="w-5 h-5" />
            <span>退出登录</span>
        </button>
      </div>
    </div>
  );
};