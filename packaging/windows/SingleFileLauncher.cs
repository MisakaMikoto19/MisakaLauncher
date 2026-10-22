// Misaka 启动器 - Windows 单文件自解压外壳
//
// 为什么自研外壳而不是直接用打包工具：
//   * jpackage 的 exe/msi 需要安装 WiX Toolset，用户机器上不一定有；
//   * 7-Zip 的自解压「安装器」模块（7zS.sfx）需额外下载 7-Zip Extra 包。
// 而 csc.exe 是 Windows 自带的（.NET Framework 4.x），因此用它能做到
// 「零额外依赖」地生成一个真正的单文件可执行程序。
//
// 行为：
//   1. 首次运行：把内嵌的 payload.zip 解压到
//      %LOCALAPPDATA%\MisakaLauncher\app\<版本>\ 并写入完成标记；
//   2. 之后运行：跳过解压直接启动（因此第二次起启动是瞬时的）；
//   3. 版本变化时目录不同，天然支持多版本共存与回滚；
//   4. 用命名互斥体保证并发启动时只解压一次。
//
// 编译约束：由 .NET Framework 的 csc 编译，语言级别为 C# 5 ——
// 不能使用字符串插值、expression-bodied 成员、nameof 等新语法。

using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Threading;
using System.Windows.Forms;

namespace Misaka
{
    internal static class SingleFileLauncher
    {
        private const string PayloadResource = "payload.zip";
        private const string VersionResource = "version.txt";
        private const string ReadyMarker = ".extracted";
        private const string MutexName = @"Local\MisakaLauncher.SingleFile.Deploy";
        private const string AppExeName = "MisakaLauncher.exe";
        /// <summary>应用名（同时用于数据目录与提示文案，与应用自身保持一致）。</summary>
        private const string AppTitle = "Misaka Launcher";

        [STAThread]
        private static int Main(string[] args)
        {
            try
            {
                // 负载标识形如 1.0.0+ab12cd34：目录名只取版本号（便于人工识别），
                // 而「是否已解压」按完整标识判断 —— 同一版本号重新构建后标识会变，
                // 从而自动重新解压，否则用户会一直看到旧副本。
                string payloadId = ReadResourceText(VersionResource);
                string folder = payloadId;
                int separator = payloadId.IndexOf('+');
                if (separator > 0)
                {
                    folder = payloadId.Substring(0, separator);
                }
                string target = Path.Combine(Path.Combine(LocalAppRoot(), "app"), folder);
                string exePath = Path.Combine(target, AppExeName);

                if (!IsReady(target, exePath, payloadId))
                {
                    DeployOnce(target, exePath, payloadId);
                }

                if (!File.Exists(exePath))
                {
                    throw new FileNotFoundException("解压完成但未找到可执行文件：" + exePath);
                }

                ProcessStartInfo info = new ProcessStartInfo(exePath);
                // 工作目录设为安装根，保证首次启动时相对路径解析与预期一致
                info.WorkingDirectory = target;
                info.UseShellExecute = false;
                info.Arguments = BuildArguments(args);

                // 「启动器同级目录」必须指用户放置单文件的位置，而不是 %LOCALAPPDATA% 里的
                // 解压目录：配置、下载缓存、游戏与 Java 运行时都应落在用户看得到的地方，
                // 且不能随版本更新被整体删除（解压目录每次发布都会被清空重建）。
                try
                {
                    string selfPath = Process.GetCurrentProcess().MainModule != null
                        ? Process.GetCurrentProcess().MainModule.FileName
                        : Assembly.GetEntryAssembly().Location;
                    string originDir = Path.GetDirectoryName(selfPath);
                    if (!string.IsNullOrEmpty(originDir) && Directory.Exists(originDir))
                    {
                        info.EnvironmentVariables["MISAKA_ORIGIN_DIR"] = originDir;
                    }
                    // 自身路径也要传下去：自更新要替换的就是这个 exe，
                    // 解压目录里的副本无法反推用户把单文件放在哪、叫什么名字
                    if (!string.IsNullOrEmpty(selfPath) && File.Exists(selfPath))
                    {
                        info.EnvironmentVariables["MISAKA_SELF_EXE"] = selfPath;
                    }
                }
                catch (Exception)
                {
                    // 拿不到自身路径（极少见）时退回应用自身的探测逻辑，不影响启动
                }

                Process.Start(info);
                return 0;
            }
            catch (Exception error)
            {
                MessageBox.Show("启动失败：\r\n" + error.Message, AppTitle,
                    MessageBoxButtons.OK, MessageBoxIcon.Error);
                return 1;
            }
        }

        /// <summary>并发启动时只让一个进程负责解压，其余等待其完成。</summary>
        private static void DeployOnce(string target, string exePath, string payloadId)
        {
            bool createdNew;
            // initiallyOwned = true：由 createdNew 判断本进程是否成为持有者
            using (Mutex gate = new Mutex(true, MutexName, out createdNew))
            {
                if (createdNew)
                {
                    try
                    {
                        Deploy(target, payloadId);
                    }
                    finally
                    {
                        gate.ReleaseMutex();
                    }
                    return;
                }
                // 等待持有者完成；超时后仍然继续（此时会走下面的二次检查）
                gate.WaitOne(TimeSpan.FromMinutes(10));
                if (!IsReady(target, exePath, payloadId))
                {
                    Deploy(target, payloadId);
                }
            }
        }

        private static void Deploy(string target, string payloadId)
        {
            // '+' 在文件名里虽合法但不便于识别，临时归档名里替换掉
            string zipPath = Path.Combine(Path.GetTempPath(),
                "misaka-launcher-" + payloadId.Replace('+', '-') + ".zip");
            WriteResourceToFile(PayloadResource, zipPath);

            // 目标目录可能是上次解压中断的残留：整体删除后重建，保证状态干净
            if (Directory.Exists(target))
            {
                Directory.Delete(target, true);
            }
            Directory.CreateDirectory(target);

            string displayVersion = payloadId;
            int separator = payloadId.IndexOf('+');
            if (separator > 0)
            {
                displayVersion = payloadId.Substring(0, separator);
            }

            string failure = null;
            using (ExtractionForm form = new ExtractionForm(displayVersion))
            {
                form.Shown += delegate
                {
                    ThreadPool.QueueUserWorkItem(delegate
                    {
                        try
                        {
                            ZipFile.ExtractToDirectory(zipPath, target);
                        }
                        catch (Exception error)
                        {
                            failure = error.Message;
                        }
                        // 关闭窗口必须在 UI 线程上执行
                        form.BeginInvoke(new Action(form.Close));
                    });
                };
                form.ShowDialog();
            }

            if (failure != null)
            {
                throw new IOException(failure);
            }

            File.WriteAllText(Path.Combine(target, ReadyMarker), payloadId);
            try
            {
                File.Delete(zipPath);
            }
            catch (IOException)
            {
                // 临时文件删不掉不影响启动
            }
        }

        /// <summary>
        /// 判断目标目录是否已经是「当前这个负载」解压出来的。
        /// <p>必须比对负载标识而不只是看标记文件是否存在：否则发布新构建后
        /// 旧缓存仍会被认为有效，用户会一直看到上一个版本的界面。</p>
        /// </summary>
        private static bool IsReady(string target, string exePath, string payloadId)
        {
            if (!File.Exists(exePath))
            {
                return false;
            }
            string marker = Path.Combine(target, ReadyMarker);
            if (!File.Exists(marker))
            {
                return false;
            }
            try
            {
                return string.Equals(File.ReadAllText(marker).Trim(), payloadId,
                    StringComparison.Ordinal);
            }
            catch (IOException)
            {
                return false;
            }
        }

        private static string LocalAppRoot()
        {
            return Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "MisakaLauncher");
        }

        private static string BuildArguments(string[] args)
        {
            if (args == null || args.Length == 0)
            {
                return string.Empty;
            }
            System.Text.StringBuilder builder = new System.Text.StringBuilder();
            foreach (string arg in args)
            {
                if (builder.Length > 0)
                {
                    builder.Append(' ');
                }
                if (arg.IndexOf(' ') >= 0 || arg.IndexOf('\t') >= 0)
                {
                    builder.Append('"').Append(arg).Append('"');
                }
                else
                {
                    builder.Append(arg);
                }
            }
            return builder.ToString();
        }

        private static string ReadResourceText(string name)
        {
            using (Stream stream = OpenResource(name))
            using (StreamReader reader = new StreamReader(stream))
            {
                return reader.ReadToEnd().Trim();
            }
        }

        private static void WriteResourceToFile(string name, string path)
        {
            using (Stream source = OpenResource(name))
            using (FileStream target = File.Create(path))
            {
                source.CopyTo(target);
            }
        }

        /// <summary>按后缀匹配资源名：csc 生成的资源名可能带命名空间前缀。</summary>
        private static Stream OpenResource(string name)
        {
            Assembly assembly = Assembly.GetExecutingAssembly();
            string[] names = assembly.GetManifestResourceNames();
            foreach (string candidate in names)
            {
                if (candidate.EndsWith(name, StringComparison.OrdinalIgnoreCase))
                {
                    return assembly.GetManifestResourceStream(candidate);
                }
            }
            throw new FileNotFoundException("内嵌资源缺失：" + name);
        }
    }

    /// <summary>首次解压时显示的等待窗口，避免用户以为程序没有响应。</summary>
    internal sealed class ExtractionForm : Form
    {
        public ExtractionForm(string version)
        {
            Text = "Misaka Launcher";
            FormBorderStyle = FormBorderStyle.FixedDialog;
            StartPosition = FormStartPosition.CenterScreen;
            MaximizeBox = false;
            MinimizeBox = false;
            ShowInTaskbar = true;
            ClientSize = new Size(430, 132);
            BackColor = Color.FromArgb(18, 22, 33);
            ForeColor = Color.FromArgb(230, 232, 239);
            Font = new Font("Microsoft YaHei UI", 9F);

            Label title = new Label();
            title.Text = "正在准备运行环境";
            title.Font = new Font(Font.FontFamily, 11F, FontStyle.Bold);
            title.ForeColor = ForeColor;
            title.AutoSize = true;
            title.Location = new Point(24, 22);
            Controls.Add(title);

            Label hint = new Label();
            hint.Text = "首次启动需要解压自带运行时（约 100 MB），仅此一次。";
            hint.ForeColor = Color.FromArgb(154, 163, 178);
            hint.AutoSize = true;
            hint.Location = new Point(24, 54);
            Controls.Add(hint);

            Label caption = new Label();
            caption.Text = "版本 " + version;
            caption.ForeColor = Color.FromArgb(122, 132, 150);
            caption.AutoSize = true;
            caption.Location = new Point(24, 78);
            Controls.Add(caption);

            ProgressBar bar = new ProgressBar();
            bar.Style = ProgressBarStyle.Marquee;
            bar.MarqueeAnimationSpeed = 24;
            bar.Location = new Point(24, 100);
            bar.Size = new Size(382, 6);
            Controls.Add(bar);
        }
    }
}
