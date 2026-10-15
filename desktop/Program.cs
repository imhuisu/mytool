// 일정 바탕화면 위젯
// - 테두리 없는 작은 창에 웹 일정 위젯 모드(sched/?mini=1&desk=1)를 띄운다. 동기화는 웹이 알아서 함
// - 기본: 다른 창 뒤(바탕화면 위)에 깔림. 트레이 메뉴에서 항상 위 / 위치 잠금 / 시작 시 실행
// - 설정: %APPDATA%\mytool-desktop\settings.txt, 웹 저장소(로그인·비밀번호): %LOCALAPPDATA%\mytool-desktop\webview
using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Runtime.InteropServices;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;
using Microsoft.Win32;

namespace SchedDesk
{
    static class Program
    {
        [DllImport("user32.dll")] static extern bool SetProcessDPIAware();

        [STAThread]
        static void Main()
        {
            // 두 번 실행 방지
            bool fresh;
            using (var mx = new System.Threading.Mutex(true, "mytool-sched-desk", out fresh))
            {
                if (!fresh) return;
                if (Environment.OSVersion.Version.Major >= 6) SetProcessDPIAware();
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                Application.Run(new DeskForm());
            }
        }
    }

    class Settings
    {
        public int X = -1, Y = -1, W = 360, H = 560;
        public bool Top, Lock;
        static string Dir => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "mytool-desktop");
        static string FilePath => Path.Combine(Dir, "settings.txt");

        public static Settings Load()
        {
            var s = new Settings();
            try
            {
                foreach (var line in File.ReadAllLines(FilePath))
                {
                    var kv = line.Split('='); if (kv.Length != 2) continue;
                    int.TryParse(kv[1], out var n);
                    switch (kv[0])
                    {
                        case "x": s.X = n; break; case "y": s.Y = n; break;
                        case "w": s.W = Math.Max(240, n); break; case "h": s.H = Math.Max(200, n); break;
                        case "top": s.Top = n == 1; break; case "lock": s.Lock = n == 1; break;
                    }
                }
            }
            catch { }
            return s;
        }

        public void Save()
        {
            try
            {
                Directory.CreateDirectory(Dir);
                File.WriteAllText(FilePath, $"x={X}\ny={Y}\nw={W}\nh={H}\ntop={(Top ? 1 : 0)}\nlock={(Lock ? 1 : 0)}\n");
            }
            catch { }
        }
    }

    class DeskForm : Form
    {
        const string Url = "https://mytool-3up.pages.dev/sched/?mini=1&desk=1";
        const string FullUrl = "https://mytool-3up.pages.dev/sched/?mini=0";
        const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
        const string RunName = "mytool-sched-desk";

        readonly Settings st = Settings.Load();
        readonly WebView2 web = new WebView2();
        readonly Panel grip = new Panel();
        readonly NotifyIcon tray = new NotifyIcon();
        ToolStripMenuItem miTop, miLock, miRun;

        [DllImport("user32.dll")] static extern bool ReleaseCapture();
        [DllImport("user32.dll")] static extern IntPtr SendMessage(IntPtr h, int msg, IntPtr w, IntPtr l);
        [DllImport("user32.dll")] static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint flags);
        static readonly IntPtr HWND_BOTTOM = new IntPtr(1);
        const uint SWP_NOMOVE = 2, SWP_NOSIZE = 1, SWP_NOACTIVATE = 0x10;

        public DeskForm()
        {
            Text = "일정 위젯";
            FormBorderStyle = FormBorderStyle.None;
            ShowInTaskbar = false;
            StartPosition = FormStartPosition.Manual;
            BackColor = Color.FromArgb(205, 213, 225);   // 가장자리 테두리 색 (여기를 끌어 크기 조절)
            MinimumSize = new Size(240, 200);
            var wa = Screen.PrimaryScreen.WorkingArea;
            // 처음(저장된 위치 없음)이거나 지금 화면 밖이면 주 모니터 오른쪽 위
            if ((st.X == -1 && st.Y == -1) || !IsOnScreen(st.X, st.Y)) { st.X = wa.Right - st.W - 24; st.Y = wa.Top + 24; }
            Bounds = new Rectangle(st.X, st.Y, st.W, st.H);
            TopMost = st.Top;
            try { Icon = new Icon(typeof(DeskForm).Assembly.GetManifestResourceStream("SchedDesk.app.ico")); } catch { }

            // 위쪽 손잡이: 끌어서 이동 (위치 잠금이면 숨김)
            grip.Dock = DockStyle.Top; grip.Height = 10; grip.Cursor = Cursors.SizeAll;
            grip.BackColor = Color.FromArgb(220, 226, 235);
            grip.MouseDown += (s, e) => { if (e.Button == MouseButtons.Left) { ReleaseCapture(); SendMessage(Handle, 0xA1, (IntPtr)2, IntPtr.Zero); } };
            grip.MouseUp += (s, e) => { if (e.Button == MouseButtons.Right) tray.ContextMenuStrip.Show(Cursor.Position); };

            web.Dock = DockStyle.Fill;
            Controls.Add(web);
            Controls.Add(grip);
            ApplyLock();

            BuildTray();
            Load += async (s, e) =>
            {
                try
                {
                    var data = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "mytool-desktop", "webview");
                    var env = await CoreWebView2Environment.CreateAsync(null, data);
                    await web.EnsureCoreWebView2Async(env);
                    web.CoreWebView2.Settings.AreDefaultContextMenusEnabled = false;
                    web.CoreWebView2.Settings.IsZoomControlEnabled = true;
                    // 새 창으로 열리는 링크는 기본 브라우저로
                    web.CoreWebView2.NewWindowRequested += (o, a) => { a.Handled = true; OpenBrowser(a.Uri); };
                    web.CoreWebView2.Navigate(Url);
                }
                catch (Exception ex)
                {
                    MessageBox.Show("WebView2를 시작하지 못했어요.\n" + ex.Message, "일정 위젯");
                }
            };
            Move += (s, e) => Remember();
            ResizeEnd += (s, e) => Remember();
            Shown += (s, e) => { if (!TopMost) SendToBottom(); };
            Activated += (s, e) => { if (!TopMost) SendToBottom(); };
        }

        static bool IsOnScreen(int x, int y)
        {
            foreach (var sc in Screen.AllScreens) if (sc.WorkingArea.Contains(x + 20, y + 20)) return true;
            return false;
        }

        // 잠금이면 손잡이·테두리 숨김, 아니면 위 손잡이 + 가장자리 5px (웹 화면이 덮지 않는 곳이라야 끌 수 있음)
        void ApplyLock()
        {
            grip.Visible = !st.Lock;
            Padding = new Padding(st.Lock ? 0 : 5);
        }

        void Remember()
        {
            if (WindowState != FormWindowState.Normal) return;
            st.X = Left; st.Y = Top; st.W = Width; st.H = Height; st.Save();
        }

        void SendToBottom() => SetWindowPos(Handle, HWND_BOTTOM, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);

        protected override CreateParams CreateParams
        {
            get
            {
                var cp = base.CreateParams;
                cp.ExStyle |= 0x80; // WS_EX_TOOLWINDOW: Alt+Tab 에 안 보이게
                return cp;
            }
        }

        protected override void WndProc(ref Message m)
        {
            const int WM_NCHITTEST = 0x84, WM_WINDOWPOSCHANGING = 0x46, WM_SYSCOMMAND = 0x112;
            if (m.Msg == WM_WINDOWPOSCHANGING && !TopMost)
            {
                // 항상 맨 뒤(바탕화면 바로 위)에 머물게
                var wp = (WINDOWPOS)Marshal.PtrToStructure(m.LParam, typeof(WINDOWPOS));
                wp.hwndInsertAfter = HWND_BOTTOM;
                Marshal.StructureToPtr(wp, m.LParam, false);
            }
            if (m.Msg == WM_SYSCOMMAND && ((int)m.WParam & 0xFFF0) == 0xF020) return; // 최소화 무시 (바탕화면 보기 등)
            base.WndProc(ref m);
            if (m.Msg == WM_NCHITTEST && !st.Lock && (int)m.Result == 1)
            {
                // 가장자리 6px 로 크기 조절
                int lp = m.LParam.ToInt32();
                var p = PointToClient(new Point((short)(lp & 0xFFFF), (short)((lp >> 16) & 0xFFFF)));
                const int g = 6; bool l = p.X < g, r = p.X > Width - g, t = p.Y < g, b = p.Y > Height - g;
                if (t && l) m.Result = (IntPtr)13; else if (t && r) m.Result = (IntPtr)14;
                else if (b && l) m.Result = (IntPtr)16; else if (b && r) m.Result = (IntPtr)17;
                else if (l) m.Result = (IntPtr)10; else if (r) m.Result = (IntPtr)11;
                else if (t) m.Result = (IntPtr)12; else if (b) m.Result = (IntPtr)15;
            }
        }

        [StructLayout(LayoutKind.Sequential)]
        struct WINDOWPOS { public IntPtr hwnd, hwndInsertAfter; public int x, y, cx, cy; public uint flags; }

        void BuildTray()
        {
            var menu = new ContextMenuStrip();
            menu.Items.Add("큰 화면으로 열기", null, (s, e) => OpenBrowser(FullUrl));
            menu.Items.Add("새로고침", null, (s, e) => web.CoreWebView2?.Reload());
            menu.Items.Add(new ToolStripSeparator());
            miTop = new ToolStripMenuItem("항상 위에 표시", null, (s, e) => { st.Top = !st.Top; TopMost = st.Top; if (!st.Top) SendToBottom(); Sync(); });
            miLock = new ToolStripMenuItem("위치·크기 잠금", null, (s, e) => { st.Lock = !st.Lock; ApplyLock(); Sync(); });
            miRun = new ToolStripMenuItem("Windows 시작 시 실행", null, (s, e) => { SetAutoRun(!IsAutoRun()); Sync(); });
            menu.Items.Add(miTop); menu.Items.Add(miLock); menu.Items.Add(miRun);
            menu.Items.Add("오른쪽 위로 되돌리기", null, (s, e) =>
            {
                var wa = Screen.PrimaryScreen.WorkingArea; Bounds = new Rectangle(wa.Right - 384, wa.Top + 24, 360, 560); Remember();
            });
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("끝내기", null, (s, e) => { tray.Visible = false; Application.Exit(); });
            tray.ContextMenuStrip = menu;
            tray.Icon = Icon ?? SystemIcons.Application;
            tray.Text = "일정 위젯";
            tray.Visible = true;
            tray.MouseClick += (s, e) => { if (e.Button == MouseButtons.Left) { Show(); WindowState = FormWindowState.Normal; Activate(); } };
            Sync();
        }

        void Sync()
        {
            st.Save();
            miTop.Checked = st.Top; miLock.Checked = st.Lock; miRun.Checked = IsAutoRun();
        }

        static bool IsAutoRun()
        {
            using (var k = Registry.CurrentUser.OpenSubKey(RunKey)) return k?.GetValue(RunName) != null;
        }

        static void SetAutoRun(bool on)
        {
            using (var k = Registry.CurrentUser.CreateSubKey(RunKey))
            {
                if (on) k.SetValue(RunName, "\"" + Application.ExecutablePath + "\"");
                else k.DeleteValue(RunName, false);
            }
        }

        static void OpenBrowser(string url)
        {
            try { Process.Start(new ProcessStartInfo(url) { UseShellExecute = true }); } catch { }
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            // Alt+F4 등으로 닫히지 않게: 트레이 "끝내기"로만 종료
            if (e.CloseReason == CloseReason.UserClosing && tray.Visible) { e.Cancel = true; return; }
            tray.Visible = false;
            base.OnFormClosing(e);
        }
    }
}
