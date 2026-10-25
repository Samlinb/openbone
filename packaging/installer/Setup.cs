// Self-contained offline per-user installer; .NET Framework 4.x is a Windows platform dependency.
// No registry/PATH/JAVA_HOME changes. Only files listed by this payload are eligible for uninstall.
using System;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Reflection;
using System.Collections.Generic;
using System.Security.Cryptography;
using System.Text;
using System.Windows.Forms;
using System.Drawing;

class Setup {
 const string Marker=".openbone-install-v1";
 static string Hash(string p){using(var s=File.OpenRead(p))using(var h=SHA256.Create())return BitConverter.ToString(h.ComputeHash(s)).Replace("-","").ToLowerInvariant();}
 static string Root(string path){string p=Path.GetFullPath(path).TrimEnd(Path.DirectorySeparatorChar);if(p==Path.GetPathRoot(p).TrimEnd(Path.DirectorySeparatorChar))throw new IOException("Choose a dedicated application folder.");return p;}
 static string Safe(string root,string relative){if(Path.IsPathRooted(relative)||relative.Split('/','\\').Any(part=>part==".."))throw new IOException("Unsafe payload path");string p=Path.GetFullPath(Path.Combine(root,relative.Replace('/',Path.DirectorySeparatorChar)));if(!p.StartsWith(root+Path.DirectorySeparatorChar,StringComparison.OrdinalIgnoreCase))throw new IOException("Path outside install directory");return p;}
 static Dictionary<string,string> Entries(ZipArchive archive){var e=archive.GetEntry("payload.sha256");if(e==null)throw new IOException("Missing payload manifest");var result=new Dictionary<string,string>(StringComparer.OrdinalIgnoreCase);using(var r=new StreamReader(e.Open(),Encoding.UTF8))while(!r.EndOfStream){string row=r.ReadLine();if(row.Length<67||row[64]!=' '||row[65]!=' ')throw new IOException("Bad manifest");result.Add(row.Substring(66),row.Substring(0,64));}return result;}
 static void Install(string destination){string root=Root(destination);using(var source=Assembly.GetExecutingAssembly().GetManifestResourceStream("payload.zip"))using(var archive=new ZipArchive(source,ZipArchiveMode.Read)){
  var entries=Entries(archive);foreach(var e in entries){Safe(root,e.Key);if(archive.GetEntry(e.Key)==null)throw new IOException("Incomplete payload");}
  if(Directory.Exists(root)&&Directory.EnumerateFileSystemEntries(root).Any()&&!File.Exists(Path.Combine(root,Marker)))throw new IOException("The nonempty target is not a Openbone installation. Choose an empty folder.");
  // Validate all payload bytes in a sibling staging directory before changing an existing installation.
  string parent=Path.GetDirectoryName(root);Directory.CreateDirectory(parent);string stage=Path.Combine(parent,".openbone-install-"+Guid.NewGuid().ToString("N"));Directory.CreateDirectory(stage);
  try{foreach(var e in entries){string p=Safe(stage,e.Key);Directory.CreateDirectory(Path.GetDirectoryName(p));using(var input=archive.GetEntry(e.Key).Open())using(var output=File.Create(p))input.CopyTo(output);if(Hash(p)!=e.Value)throw new IOException("Payload checksum mismatch: "+e.Key);}
   // Refuse user-modified product paths rather than overwriting them during reinstall.
   Dictionary<string,string> old=new Dictionary<string,string>(StringComparer.OrdinalIgnoreCase);string marker=Path.Combine(root,Marker);if(File.Exists(marker))foreach(string line in File.ReadAllLines(marker,Encoding.UTF8)){if(line.Length>=67)old[line.Substring(66)]=line.Substring(0,64);}
   foreach(var e in entries){string p=Safe(root,e.Key);if(File.Exists(p)&&Hash(p)!=e.Value&&(!old.ContainsKey(e.Key)||Hash(p)!=old[e.Key]))throw new IOException("User-modified file retained; choose another target: "+e.Key);}
   Directory.CreateDirectory(root);foreach(var e in entries){string p=Safe(root,e.Key);Directory.CreateDirectory(Path.GetDirectoryName(p));File.Copy(Safe(stage,e.Key),p,true);}
   File.WriteAllLines(marker,entries.Select(e=>e.Value+"  "+e.Key),new UTF8Encoding(false));
  }finally{if(Path.GetFullPath(stage).StartsWith(parent+Path.DirectorySeparatorChar)&&Path.GetFileName(stage).StartsWith(".openbone-install-"))Directory.Delete(stage,true);}
 }}
 static void Uninstall(string destination){string root=Root(destination),marker=Path.Combine(root,Marker);if(!File.Exists(marker))throw new IOException("No installation receipt at target.");
  var retained=new List<string>();foreach(string row in File.ReadAllLines(marker,Encoding.UTF8)){if(row.Length<67)throw new IOException("Bad installation receipt");string p=Safe(root,row.Substring(66));if(File.Exists(p)){if(Hash(p)==row.Substring(0,64))File.Delete(p);else retained.Add(row);}}
  // Unexpected user projects and changed files are preserved, including files inside the app tree.
  foreach(string dir in Directory.GetDirectories(root,"*",SearchOption.AllDirectories).OrderByDescending(p=>p.Length))if(!Directory.EnumerateFileSystemEntries(dir).Any())Directory.Delete(dir);
  if(retained.Count==0)File.Delete(marker);else File.WriteAllLines(marker,retained,new UTF8Encoding(false));if(!Directory.EnumerateFileSystemEntries(root).Any())Directory.Delete(root);
 }
 [STAThread] static int Main(string[] args){try{
  if(args.Length==2&&(args[0]=="--install"||args[0]=="--uninstall")){if(args[0]=="--install")Install(args[1]);else Uninstall(args[1]);return 0;}
  if(args.Length!=0)return 2;
  Application.EnableVisualStyles();var form=new Form{Text="Openbone P9 安装 / 卸载",Width=720,Height=245,StartPosition=FormStartPosition.CenterScreen};
  var path=new TextBox{Left=24,Top=60,Width=550,Text=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"Programs","Openbone-P9")};
  var label=new Label{Left=24,Top=20,Width=650,Height=32,Text="离线安装，内含 Java 17。选择专用目录；工程与恢复数据将保留。"};
  var browse=new Button{Text="浏览…",Left=585,Top=58,Width=90};browse.Click+=(s,e)=>{using(var d=new FolderBrowserDialog()){if(d.ShowDialog(form)==DialogResult.OK)path.Text=d.SelectedPath;}};
  var install=new Button{Text="安装 / 重装",Left=24,Top=110,Width=145};var uninstall=new Button{Text="卸载",Left=185,Top=110,Width=100};var close=new Button{Text="关闭",Left=570,Top=155,Width=100};var status=new Label{Left=24,Top=155,Width=530,Height=40};
  install.Click+=(s,e)=>{try{Install(path.Text);status.Text="安装完成。双击目标目录中的 Openbone.exe 启动。";}catch(Exception ex){MessageBox.Show(form,ex.Message,"安装失败");}};
  uninstall.Click+=(s,e)=>{if(MessageBox.Show(form,"删除已安装的产品文件？用户工程和修改过的文件将保留。","卸载",MessageBoxButtons.YesNo)!=DialogResult.Yes)return;try{Uninstall(path.Text);status.Text="卸载完成；用户文件已保留。";}catch(Exception ex){MessageBox.Show(form,ex.Message,"卸载未完成");}};
  close.Click+=(s,e)=>form.Close();form.Controls.AddRange(new Control[]{label,path,browse,install,uninstall,close,status});Application.Run(form);return 0;
 }catch(Exception e){try{File.WriteAllText(Path.Combine(Path.GetTempPath(),"Openbone-setup-error-"+System.Diagnostics.Process.GetCurrentProcess().Id+".txt"),e.ToString());}catch{}return 1;}}
}

