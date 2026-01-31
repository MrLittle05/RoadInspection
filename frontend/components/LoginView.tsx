import React, { useState } from 'react';
import { User, Lock, ArrowRight, UserPlus, ShieldCheck } from 'lucide-react';
import { ToastType } from './Toast';

interface LoginViewProps {
  onLogin: (username: string, password: string) => Promise<boolean>;
  onRegister: (username: string, password: string) => Promise<boolean>;
  showToast: (title: string, message: string, type: ToastType) => void;
}

export const LoginView: React.FC<LoginViewProps> = ({ onLogin, onRegister, showToast }) => {
  const [isLoginMode, setIsLoginMode] = useState(true);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [isLoading, setIsLoading] = useState(false);

  const checkPasswordComplexity = (pwd: string) => {
    // Complexity: At least 6 characters, must contain letter and number
    const hasLetter = /[a-zA-Z]/.test(pwd);
    const hasNumber = /\d/.test(pwd);
    return pwd.length >= 6 && hasLetter && hasNumber;
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!username || !password) {
      showToast('输入错误', '用户名和密码不能为空', 'error');
      return;
    }

    if (!isLoginMode) {
        if (password !== confirmPassword) {
            showToast('验证失败', '两次输入的密码不一致', 'error');
            return;
        }
        if (!checkPasswordComplexity(password)) {
            showToast('密码强度不足', '密码需包含字母和数字，且长度不少于6位', 'error');
            return;
        }
    }

    setIsLoading(true);
    try {
      if (isLoginMode) {
        await onLogin(username, password);
      } else {
        await onRegister(username, password);
      }
    } catch (error) {
       // Error handled in App.tsx typically, but safe guard here
       console.error(error);
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="min-h-screen flex flex-col items-center justify-center p-6 bg-slate-50">
      <div className="w-full max-w-sm">
        {/* Logo / Header */}
        <div className="text-center mb-10">
          <div className="w-16 h-16 bg-blue-600 rounded-2xl mx-auto flex items-center justify-center shadow-lg shadow-blue-500/30 mb-4">
            <ShieldCheck className="w-9 h-9 text-white" />
          </div>
          <h1 className="text-2xl font-bold text-slate-800">RoadGuard</h1>
          <p className="text-slate-400 text-sm mt-2">智能道路巡检系统</p>
        </div>

        {/* Form Card */}
        <div className="bg-white rounded-2xl shadow-xl p-8">
          <h2 className="text-xl font-bold text-slate-800 mb-6">
            {isLoginMode ? '欢迎回来' : '注册账号'}
          </h2>

          <form onSubmit={handleSubmit} className="space-y-4">
            <div className="space-y-2">
              <label className="text-xs font-semibold text-slate-500 uppercase">用户名</label>
              <div className="relative">
                <User className="absolute left-3 top-3 w-5 h-5 text-slate-400" />
                <input 
                  type="text" 
                  value={username}
                  onChange={(e) => setUsername(e.target.value)}
                  className="w-full bg-slate-50 border border-slate-200 rounded-xl py-2.5 pl-10 pr-4 text-slate-800 focus:outline-none focus:ring-2 focus:ring-blue-500 transition-all"
                  placeholder="请输入用户名"
                />
              </div>
            </div>

            <div className="space-y-2">
              <div className="flex justify-between items-center">
                <label className="text-xs font-semibold text-slate-500 uppercase">密码</label>
                {!isLoginMode && (
                    <span className="text-[10px] text-slate-400">需包含字母数字，至少6位</span>
                )}
              </div>
              <div className="relative">
                <Lock className="absolute left-3 top-3 w-5 h-5 text-slate-400" />
                <input 
                  type="password" 
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  className="w-full bg-slate-50 border border-slate-200 rounded-xl py-2.5 pl-10 pr-4 text-slate-800 focus:outline-none focus:ring-2 focus:ring-blue-500 transition-all"
                  placeholder="请输入密码"
                />
              </div>
            </div>

            {!isLoginMode && (
                <div className="space-y-2 animate-in slide-in-from-top-2 fade-in duration-200">
                    <label className="text-xs font-semibold text-slate-500 uppercase">确认密码</label>
                    <div className="relative">
                        <Lock className="absolute left-3 top-3 w-5 h-5 text-slate-400" />
                        <input 
                            type="password" 
                            value={confirmPassword}
                            onChange={(e) => setConfirmPassword(e.target.value)}
                            className="w-full bg-slate-50 border border-slate-200 rounded-xl py-2.5 pl-10 pr-4 text-slate-800 focus:outline-none focus:ring-2 focus:ring-blue-500 transition-all"
                            placeholder="请再次输入密码"
                        />
                    </div>
                </div>
            )}

            <button 
              type="submit"
              disabled={isLoading}
              className="w-full bg-blue-600 hover:bg-blue-700 text-white font-bold py-3 rounded-xl shadow-lg shadow-blue-500/30 flex items-center justify-center space-x-2 active:scale-[0.98] transition-all mt-4 disabled:opacity-70 disabled:cursor-not-allowed"
            >
              {isLoading ? (
                <div className="w-5 h-5 border-2 border-white/30 border-t-white rounded-full animate-spin"></div>
              ) : (
                <>
                  <span>{isLoginMode ? '立即登录' : '注册并登录'}</span>
                  {isLoginMode ? <ArrowRight className="w-5 h-5" /> : <UserPlus className="w-5 h-5" />}
                </>
              )}
            </button>
          </form>
        </div>

        {/* Toggle Mode */}
        <div className="text-center mt-6">
          <p className="text-sm text-slate-500">
            {isLoginMode ? '还没有账号？' : '已有账号？'}
            <button 
              onClick={() => {
                setIsLoginMode(!isLoginMode);
                setUsername('');
                setPassword('');
                setConfirmPassword('');
              }}
              className="ml-1 text-blue-600 font-semibold hover:underline"
            >
              {isLoginMode ? '去注册' : '去登录'}
            </button>
          </p>
        </div>
      </div>
    </div>
  );
};