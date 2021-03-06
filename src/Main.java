
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

import Bin.*;
import Component.File.*;
import Component.File.BedFile.BedFile;
import Component.File.BedFile.BedItem;
import Component.File.BedPeFile.BedpeFile;
import Component.File.CommonFile.CommonFile;
import Component.File.FastQFile.FastqFile;
import Component.File.FastaFile.FastaFile;
import Component.File.SamFile.SamFile;
import Component.FragmentDigested.FragmentDigested;
import Component.FragmentDigested.RestrictionEnzyme;
import Component.Process.BedpeProcess;
import Component.Process.PreProcess;
import Component.Process.SeProcess;
import Component.Statistic.Chart.BarChart;
import Component.Statistic.Report;
import Component.SystemDhat.CommandLineDhat;
import Component.SystemDhat.Qsub;
import Component.tool.*;
import Component.unit.*;
import org.apache.commons.cli.*;
import org.apache.commons.io.FileUtils;

/**
 * Created by snowf on 2019/2/17.
 */

public class Main {

    //===================================================================
    private Chromosome[] Chromosomes;
    private LinkerSequence[] LinkerSeq, ValidLinkerSeq;
    private String LinkerA, LinkerB;
    private String Restriction;
    private String Prefix;
    private FastqFile InputFile;
    private File LinkerFile;
    private File AdapterFile;
    private String[] AdapterSeq;
    private int MatchScore, MisMatchScore, InDelScore;
    private Opts.FileFormat ReadsType;
    private int AlignMisMatch;
    private int MinUniqueScore;
    private int MaxReadsLength;
    private int[] Resolution, DrawResolution;
    private int LinkerLength;
    private boolean Iteration = false;
    private int Threads;
    private ArrayList<Thread> SThread = new ArrayList<>();//统计线程队列
    //===================================================================
    private int MinLinkerFilterQuality;
    private File EnzyPath;//酶切位点文件目录
    private String EnzyFilePrefix;//酶切位点文件前缀
    private BedFile[] ChrEnzyFile;//每条染色体的酶切位点位置文件
    private File PreProcessDir;//预处理输出目录
    private File SeProcessDir;//单端处理输出目录
    private File BedpeProcessDir;//bedpe处理输出目录
    private File MakeMatrixDir;//建立矩阵输出目录
    private File ReportDir;//生成报告目录
    private Report Stat;
    private File OutPath;
    private int DeBugLevel;


    private Main(String[] args) throws IOException {
        Options Argument = new Options();
        Argument.addOption(Option.builder("i").hasArg().argName("file").desc("input file").build());//输入文件
        Argument.addOption(Option.builder("conf").hasArg().argName("file").desc("Configure file").build());//配置文件
        Argument.addOption(Option.builder("p").hasArg().argName("string").desc("Prefix").build());//输出前缀(不需要包括路径)
        Argument.addOption(Option.builder("o").longOpt("out").hasArg().argName("dir").desc("Out put directory").build());//输出路径
        Argument.addOption(Option.builder("r").longOpt("res").hasArgs().argName("ints").desc("resolution").build());//分辨率
        Argument.addOption(Option.builder("s").longOpt("step").hasArgs().argName("string").desc("same as \"Step\" in configure file").build());//运行步骤
        Argument.addOption(Option.builder("t").longOpt("thread").hasArg().argName("int").desc("number of threads").build());//线程数
        Argument.addOption(Option.builder("D").longOpt("Debug").hasArg().argName("int").desc("Debug Level (default " + DeBugLevel + ")").build());
        Argument.addOption(Option.builder("pbs").hasArg(false).desc("running by pbs").build());
        final String helpHeader = "Version: " + Opts.Version + "\nAuthor: " + Opts.Author + "\nContact: " + Opts.Email;
        final String helpFooter = "Note: use \"java -jar " + Opts.JarFile.getName() + " install\" when you first use!\n      JVM can get " + String.format("%.2f", Opts.MaxMemory / Math.pow(10, 9)) + "G memory";
        if (args.length == 0) {
            //没有参数时打印帮助信息
            new HelpFormatter().printHelp("java -jar Path/" + Opts.JarFile.getName(), helpHeader, Argument, helpFooter, true);
            System.exit(1);
        }
        CommandLine ComLine = null;
        try {
            ComLine = new DefaultParser().parse(Argument, args);
        } catch (ParseException e) {
            //缺少参数时打印帮助信息
            System.err.println(e.getMessage());
            new HelpFormatter().printHelp("java -jar Path/" + Opts.JarFile.getName(), helpHeader, Argument, helpFooter, true);
            System.exit(1);
        }
        //获取配置文件和高级配置文件
        File ConfigureFile = ComLine.hasOption("conf") ? new File(ComLine.getOptionValue("conf")) : Opts.ConfigFile;
        Configure.GetOption(ConfigureFile);//读取配置信息
        //获取命令行参数信息
        if (ComLine.hasOption("i"))
            Configure.Require.InputFile.Value = ComLine.getOptionValue("i");
        if (ComLine.hasOption("p"))
            Configure.Optional.Prefix.Value = ComLine.getOptionValue("p");
        if (ComLine.hasOption("o"))
            Configure.Optional.OutPath.Value = ComLine.getOptionValue("o");
        if (ComLine.hasOption("s")) {
            Configure.Optional.Step.Value = String.join(" ", ComLine.getOptionValues("s"));
        }
        if (ComLine.hasOption("t"))
            Configure.Optional.Thread.Value = ComLine.getOptionValue("t");
        if (ComLine.hasOption("r"))
            Configure.Optional.Resolutions.Value = String.join(" ", ComLine.getOptionValues("r"));
        if (ComLine.hasOption("D"))
            Configure.Advance.DeBugLevel.Value = ComLine.getOptionValue("D");
        if (ComLine.hasOption("pbs")) {
            try {
                String comline = "java -Xmx" + (int) Math.ceil(Opts.MaxMemory / Math.pow(10, 9)) + "G -jar " + Opts.JarFile + " " + String.join(" ", args).replace("-pbs", "");
                CommonFile PbsFile = new CommonFile("SubmitScript.sh");
                if (!PbsFile.clean()) {
                    System.err.println("can't clean " + PbsFile.getName());
                    System.exit(1);
                }
                PbsFile.Append(comline);
                String SubmitId = new Qsub(PbsFile, "1", Integer.parseInt(Configure.Optional.Thread.Value.toString()), Opts.MaxMemory, Configure.Optional.Prefix.Value.toString()).run();
                System.out.println(SubmitId);
                System.exit(0);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        Configure.Init();
        Init();//变量初始化
    }

    public Main() throws IOException {
        Init();
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        //==============================================测试区==========================================================


        //================================================初始化========================================================
        if (args.length >= 1 && args[0].equals("install")) {
            if (!Opts.OutResourceDir.isDirectory() & !Opts.OutResourceDir.mkdir())
                System.err.println(new Date() + ":\tCan't Create " + Opts.OutResourceDir.getName());
            if (!Opts.OutScriptDir.isDirectory() & !Opts.OutScriptDir.mkdir())
                System.err.println(new Date() + ":\tCan't Create " + Opts.OutScriptDir.getName());
            for (String f : Opts.ScriptFile) {
                System.out.println("Extract " + Opts.OutScriptDir + "/" + f);
                FileTool.ExtractFile("/" + Opts.InterArchiveDir + "/" + f, new File(Opts.OutScriptDir + "/" + f));
            }
            Opts.ConfigFile.clean();
            Opts.ConfigFile.Append(Configure.ShowParameter());
            System.out.println("Extract " + Opts.JarFile.getParent() + "/" + Opts.ReadMeFile.getName());
            FileTool.ExtractFile(Opts.ReadMeFile.getPath(), new File(Opts.JarFile.getParent() + "/" + Opts.ReadMeFile.getName()));
            System.out.println("Install finish!");
            System.exit(0);
        } else if (args.length >= 1 && args[0].equals("clean")) {
            for (Opts.OutDir d : Opts.OutDir.values()) {
                System.out.println("Cleaning " + d.toString());
                FileUtils.deleteDirectory(new File(d.toString()));
            }
            System.out.println("clean finish!");
            System.exit(0);
        }
        //==============================================================================================================
        Main main = new Main(args);
        main.ShowParameter();//显示参数
        try {
            main.Run();
        } catch (Exception e) {
            Opts.RSStat.Finish();
            throw e;
        }
    }

    public void Run() throws IOException, InterruptedException {
        //============================================print system information==========================================
        System.out.println("===============Welcome to use " + Opts.JarFile.getName() + "===================");
        System.out.println("Version:\t" + Opts.Version);
        System.out.println("Author:\t" + Opts.Author);
        System.out.println("Max Memory:\t" + String.format("%.2f", Opts.MaxMemory / Math.pow(10, 9)) + "G");
        System.out.println("-------------------------------------------------------------------------------");
        //==============================================================================================================
        AbstractFile.delete(Opts.CommandOutFile);
        AbstractFile.delete(Opts.StatisticFile);
        AbstractFile.delete(Opts.ResourceStatFile);
        Opts.RSStat.Init();
        Opts.RSStat.Stat();
        //===========================================初始化输出文件======================================================
        FastqFile[] LinkerFastqFileR1, UseLinkerFasqFileR1 = new FastqFile[ValidLinkerSeq.length];
        FastqFile[] LinkerFastqFileR2, UseLinkerFasqFileR2 = new FastqFile[ValidLinkerSeq.length];
        //==============================================================================================================
        Thread ST;//统计线程
        Thread[] STS;//统计线程
        Thread findenzy = FindRestrictionFragment();
        //=========================================linker filter==linker 过滤===========================================
        Date preTime = new Date();
        //---------------------------------保存linker序列--------------------------------
        FileUtils.touch(LinkerFile);
        for (LinkerSequence linkerSeq : LinkerSeq) {
            FileUtils.write(LinkerFile, linkerSeq.getSeq() + "\t" + linkerSeq.getType() + "\n", StandardCharsets.UTF_8, true);
        }
        PreProcess preprocess = new PreProcess(PreProcessDir, Prefix, InputFile, LinkerFile, AdapterFile, Restriction);
        preprocess.setMinLinkerMappingScore(MinLinkerFilterQuality);
        //-----------------------------------Adapter序列处理------------------------------
        if (Opts.Step.PreProcess.Execute) {
            if (AdapterSeq != null) {
                //若Adapter序列不为空
                if (AdapterSeq[0].compareToIgnoreCase("auto") == 0) {
                    //标记为自动识别Adapter
                    AdapterSeq = Opts.LFStat.AdapterStat(InputFile, Stat, Prefix, LinkerLength + MaxReadsLength);
//                    AdapterSeq = new String[1];
//                    CommonFile StatFile = new CommonFile(Stat.getDataDir() + "/" + Prefix + ".adapter_detection.base.freq");
//                    AdapterSeq[0] = FileTool.AdapterDetection(InputFile, new File(PreProcessDir + "/" + Prefix), LinkerLength + MaxReadsLength, StatFile);
//                    Opts.LFStat.AdapterBaseDisPng = new File(Stat.getImageDir() + "/" + StatFile.getName() + ".png");
//                    String ComLine = Configure.Python.FullExe() + " " + Opts.StatisticPlotFile + " -t stackbar -y Percentage --title Base_Frequency" + " -i " + StatFile + " -o " + Opts.LFStat.AdapterBaseDisPng;
//                    Opts.CommandOutFile.Append(ComLine + "\n");
//                    new CommandLineDhat().run(ComLine, null, new PrintWriter(System.err));
                    System.out.println(new Date() + "\tDetected adapter seq:\t" + AdapterSeq[0]);
                }
                //将Adapter序列输出到文件中
                FileUtils.write(AdapterFile, String.join("\n", AdapterSeq), StandardCharsets.UTF_8);
//                Opts.LFStat.Adapters = AdapterSeq;
            }
            //-----------------------------------------------------------------------------
            preprocess.run();//运行预处理部分
            //----------------------------------------------------------------------
        }
        File PastFile = preprocess.getLinkerFilterOutFile();//获取past文件位置
        //=================================================统计信息=====================================================
        if (Opts.Step.Statistic.Execute) {
            if (AdapterSeq != null && AdapterSeq[0].compareToIgnoreCase("auto") == 0) {
                System.out.println(new Date() + " [statistic]:\tStart Adapter detection statistic");
                Opts.LFStat.AdapterStat(InputFile, Stat, Prefix, LinkerLength + MaxReadsLength);
            }
            System.out.println(new Date() + " [statistic]:\tStart Linker filter statistic");
            Opts.LFStat.InputFile = new CommonFile(PastFile);
            Opts.LFStat.Stat(Configure.Thread);
        }
        Opts.StatisticFile.Append(Opts.LFStat.Show() + "\n");
        //--------------------------------------------draw statistic figure---------------------------------------------
        CommonFile LinkerDisFile = new CommonFile(Stat.getDataDir() + "/LinkerScoreDis.data");
        Opts.LFStat.WriteLinkerScoreDis(LinkerDisFile);
        Opts.LFStat.LinkerScoreDisPng = new File(Stat.getImageDir() + "/" + LinkerDisFile.getName().replace(".data", ".png"));
        String ComLine;
        try {
            ComLine = Configure.Python.FullExe() + " " + Opts.StatisticPlotFile + " -i " + LinkerDisFile + " -t bar -o " + Opts.LFStat.LinkerScoreDisPng;
            int ExitValue = new CommandLineDhat().run(ComLine, null, new PrintWriter(System.err));
            if (!(ExitValue == 0)) {
                throw new InterruptedException("can't draw figure by python");
            }
            Opts.CommandOutFile.Append(ComLine + "\n");
        } catch (IOException | InterruptedException e) {
            System.err.println(new Date() + "\tCan't draw Linker score distribution by python, try to draw it by java");
            BarChart barChart = new BarChart();
            barChart.loadData(LinkerDisFile);
            barChart.drawing(Opts.LFStat.LinkerScoreDisPng);
        }

        CommonFile[] ReadsLenDisFile = new CommonFile[LinkerSeq.length];
        Stat.ReadsLengthDisBase64 = new String[LinkerSeq.length];
        for (int i = 0; i < ReadsLenDisFile.length; i++) {
            ReadsLenDisFile[i] = new CommonFile(Stat.getDataDir() + "/" + Prefix + "." + LinkerSeq[i].getType() + ".reads_length_distribution.data");
        }
        Opts.LFStat.WriteReadsLengthDis(ReadsLenDisFile);
        for (int i = 0; i < ReadsLenDisFile.length; i++) {
            Opts.LFStat.linkers[i].ReadLengthDisPng = new File(Stat.getImageDir() + "/" + ReadsLenDisFile[i].getName().replace(".data", ".png"));
            try {
                ComLine = Configure.Python.FullExe() + " " + Opts.StatisticPlotFile + " -t bar -y Count --title " + LinkerSeq[i].getType() + " -i " + ReadsLenDisFile[i] + " -o " + Opts.LFStat.linkers[i].ReadLengthDisPng;
                int ExitValue = new CommandLineDhat().run(ComLine, null, new PrintWriter(System.err));
                if (!(ExitValue == 0)) {
                    throw new InterruptedException("can't draw figure by python");
                }
                Opts.CommandOutFile.Append(ComLine + "\n");
            } catch (IOException | InterruptedException e) {
                System.err.println(new Date() + "\tCan't draw reads length distribution by python, try to draw it by java");
                BarChart barChart = new BarChart();
                barChart.loadData(ReadsLenDisFile[i]);
                barChart.YLabel = "Count";
                barChart.drawing(Opts.LFStat.linkers[i].ReadLengthDisPng);
            }

        }
        //==============================================================================================================
        LinkerFastqFileR1 = preprocess.getFastqR1File();
        LinkerFastqFileR2 = preprocess.getFastqR2File();
        //==============================================================================================================
        for (int i = 0; i < ValidLinkerSeq.length; i++) {
            for (int j = 0; j < LinkerSeq.length; j++) {
                if (LinkerSeq[j].getType().equals(ValidLinkerSeq[i].getType())) {
                    UseLinkerFasqFileR1[i] = LinkerFastqFileR1[j];
                    UseLinkerFasqFileR2[i] = LinkerFastqFileR2[j];
                    Opts.ALStat.linkers[i].InputFile = UseLinkerFasqFileR1[i];
                }
            }
        }
        //=======================================Se Process===单端处理==================================================
        Date seTime = new Date();
        System.err.println("Linker filter: " + preTime + " - " + seTime);
        Opts.LFStat.Time = seTime.getTime() - preTime.getTime();
        //--------------------------------------------------------------------------------------------------------------
        BedFile[] R1SortBedFile = new BedFile[ValidLinkerSeq.length];
        BedFile[] R2SortBedFile = new BedFile[ValidLinkerSeq.length];
        BedpeFile[] SeBedpeFile = new BedpeFile[ValidLinkerSeq.length];
        for (int i = 0; i < ValidLinkerSeq.length; i++) {
            R1SortBedFile[i] = new SeProcess(UseLinkerFasqFileR1[i], Configure.Bwa, AlignMisMatch, MinUniqueScore, SeProcessDir, UseLinkerFasqFileR1[i].getName().replace(".fastq", ""), ReadsType).getSortBedFile();
            R2SortBedFile[i] = new SeProcess(UseLinkerFasqFileR2[i], Configure.Bwa, AlignMisMatch, MinUniqueScore, SeProcessDir, UseLinkerFasqFileR2[i].getName().replace(".fastq", ""), ReadsType).getSortBedFile();
            SeBedpeFile[i] = new BedpeFile(SeProcessDir + "/" + Prefix + "." + ValidLinkerSeq[i].getType() + ".bedpe");
            Opts.NRStat.linkers[i].InputFile = SeBedpeFile[i];
        }
        if (Opts.Step.Alignment.Execute) {
            if (Configure.Bwa.IndexPrefix == null || Configure.Bwa.IndexCheck == Opts.FileFormat.ErrorFormat) {
                CreateIndex(Configure.Bwa.GenomeFile);
                Configure.Bwa.IndexCheck = Opts.FileFormat.Valid;
            }
            for (int i = 0; i < ValidLinkerSeq.length; i++) {
                System.out.println(new Date() + "\tStart Alignment");
                //==========================================Create Index========================================================
                Opts.ALStat.GenomeIndex = Configure.Bwa.IndexPrefix;
                SamFile[] r1 = SeProcess(UseLinkerFasqFileR1[i], UseLinkerFasqFileR1[i].getName().replace(".fastq", ""));
                SamFile[] r2 = SeProcess(UseLinkerFasqFileR2[i], UseLinkerFasqFileR2[i].getName().replace(".fastq", ""));
                Opts.ALStat.linkers[i].InputNum = UseLinkerFasqFileR1[i].getItemNum();
                Opts.ALStat.linkers[i].R1Mapped = r1[0].getItemNum();
                Opts.ALStat.linkers[i].R1MultiMapped = r1[1].getItemNum();
                Opts.ALStat.linkers[i].R1Unmapped = r1[2].getItemNum();
                Opts.ALStat.linkers[i].R2Mapped = r2[0].getItemNum();
                Opts.ALStat.linkers[i].R2MultiMapped = r2[1].getItemNum();
                Opts.ALStat.linkers[i].R2Unmapped = r2[2].getItemNum();
                System.out.println(new Date() + "\t" + R1SortBedFile[i].getName() + " " + R2SortBedFile[i].getName() + " to " + SeBedpeFile[i].getName());
                SeBedpeFile[i].BedToBedpe(R1SortBedFile[i], R2SortBedFile[i]);//合并左右端bed文件，输出bedpe文件
                Opts.ALStat.linkers[i].MergeNum = SeBedpeFile[i].getItemNum();
            }
        }
        if (Opts.Step.Statistic.Execute) {
            System.out.println(new Date() + " [statistic]:\tStart alignment statistic");
            for (int i = 0; i < Opts.ALStat.Linkers.length; i++) {
                Opts.ALStat.linkers[i].InputFile = new FastqFile(UseLinkerFasqFileR1[i]);
                SeProcess se = new SeProcess(UseLinkerFasqFileR1[i], Configure.Bwa, AlignMisMatch, MinUniqueScore, SeProcessDir, UseLinkerFasqFileR1[i].getName().replace(".fastq", ""), ReadsType);
                Opts.ALStat.linkers[i].UniqueSamFile1 = se.getUniqSamFile();
                Opts.ALStat.linkers[i].MultiSamFile1 = se.getMultiSamFile();
                Opts.ALStat.linkers[i].UnmappedSamFile1 = se.getUnMapSamFile();
                se = new SeProcess(UseLinkerFasqFileR2[i], Configure.Bwa, AlignMisMatch, MinUniqueScore, SeProcessDir, UseLinkerFasqFileR2[i].getName().replace(".fastq", ""), ReadsType);
                Opts.ALStat.linkers[i].UniqueSamFile2 = se.getUniqSamFile();
                Opts.ALStat.linkers[i].MultiSamFile2 = se.getMultiSamFile();
                Opts.ALStat.linkers[i].UnmappedSamFile2 = se.getUnMapSamFile();
                Opts.ALStat.linkers[i].BedPeFile = new BedpeFile(SeBedpeFile[i]);
                Opts.NRStat.linkers[i].InputFile = Opts.ALStat.linkers[i].BedPeFile;
            }
            Opts.ALStat.Stat(Configure.Thread);
        }
        Opts.StatisticFile.Append(Opts.ALStat.Show() + "\n");
        //=======================================bedpe process init=====================================================
        BedpeFile FinalBedpeFile = new BedpeFile(BedpeProcessDir + "/" + Prefix + ".clean.bedpe");
        BedpeFile SameBedpeFile = new BedpeFile(BedpeProcessDir + "/" + Prefix + ".same.clean.bedpe");
        BedpeFile DiffBedpeFile = new BedpeFile(BedpeProcessDir + "/" + Prefix + ".diff.clean.bedpe");
        BedpeFile[] ChrBedpeFile = new BedpeFile[Chromosomes.length];
        BedpeFile[][] LinkerChrSameCleanBedpeFile = new BedpeFile[ValidLinkerSeq.length][];
        BedpeFile[] LinkerFinalSameCleanBedpeFile = new BedpeFile[ValidLinkerSeq.length];
        BedpeFile[] LinkerFinalDiffCleanBedpeFile = new BedpeFile[ValidLinkerSeq.length];
        BedpeFile[] FinalLinkerBedpe = new BedpeFile[ValidLinkerSeq.length];//有效的bedpe文件,每种linker一个文件
        for (int i = 0; i < Chromosomes.length; i++) {
            ChrBedpeFile[i] = new BedpeFile(BedpeProcessDir + "/" + Prefix + "." + Chromosomes[i].Name + ".same.clean.bedpe");
        }
        BedpeFile InterBedpeFile = new BedpeFile(BedpeProcessDir + "/" + Prefix + ".inter.clean.bedpe");
        Date bedpeTime = new Date();
        System.err.println("Alignment: " + seTime + " - " + bedpeTime);
        Opts.ALStat.Time = bedpeTime.getTime() - seTime.getTime();
        Thread[] LinkerProcess = new Thread[ValidLinkerSeq.length];//不同linker类型并行
        BedpeProcess[] bedpe = new BedpeProcess[ValidLinkerSeq.length];//不同linker类型并行
        for (int i = 0; i < LinkerProcess.length; i++) {
            bedpe[i] = new BedpeProcess(new File(BedpeProcessDir + "/" + ValidLinkerSeq[i].getType()), Prefix + "." + ValidLinkerSeq[i].getType(), Chromosomes, SeBedpeFile[i]);//bedpe文件处理类
            bedpe[i].Threads = Math.max(1, Threads / LinkerProcess.length);//设置线程数
        }
        if (Opts.Step.NoiseReduce.Execute) {
            //==========================================获取酶切片段和染色体大小==========================================
            findenzy.start();
            findenzy.join();
            Opts.Step.FindEnzymeFragment.Execute = false;
            //==============================================Noise reduce====bedpe 处理===================================
            for (int i = 0; i < LinkerProcess.length; i++) {
                int finalI = i;
                LinkerProcess[i] = new Thread(() -> {
                    try {
                        bedpe[finalI].Run();//运行
                        Opts.NRStat.linkers[finalI].RawDataNum = SeBedpeFile[finalI].getItemNum();
                        Opts.NRStat.linkers[finalI].SelfLigationNum = bedpe[finalI].getSelfLigationFile().getItemNum();
                        Opts.NRStat.linkers[finalI].ReLigationNum = bedpe[finalI].getReLigationFile().getItemNum();
                        Opts.NRStat.linkers[finalI].DuplicateNum = bedpe[finalI].getRepeatFile().getItemNum();
                        Opts.NRStat.linkers[finalI].CleanNum = bedpe[finalI].getFinalFile().getItemNum();
                        Opts.NRStat.linkers[finalI].SameCleanNum = bedpe[finalI].getSameNoDumpFile().getItemNum();
                        Opts.NRStat.linkers[finalI].DiffCleanNum = bedpe[finalI].getDiffNoDumpFile().getItemNum();
                        long[] range_result = Opts.NRStat.RangeCount(bedpe[finalI].getSameNoDumpFile());
                        Opts.NRStat.linkers[finalI].ShortRangeNum = range_result[0];
                        Opts.NRStat.linkers[finalI].LongRangeNum = range_result[1];
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
                LinkerProcess[i].start();
                FinalLinkerBedpe[i] = bedpe[i].getFinalFile();
                LinkerFinalSameCleanBedpeFile[i] = bedpe[i].getSameNoDumpFile();
                LinkerFinalDiffCleanBedpeFile[i] = bedpe[i].getDiffNoDumpFile();
                LinkerChrSameCleanBedpeFile[i] = bedpe[i].getChrSameNoDumpFile();
            }
            Tools.ThreadsWait(LinkerProcess);
            Thread t1 = new Thread(() -> {
                try {
                    SameBedpeFile.Merge(LinkerFinalSameCleanBedpeFile);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
            t1.start();
            Thread t2 = new Thread(() -> {
                try {
                    DiffBedpeFile.Merge(LinkerFinalDiffCleanBedpeFile);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
            t2.start();
            Thread t3 = new Thread(() -> {
                try {
                    FinalBedpeFile.Merge(FinalLinkerBedpe);//合并不同linker类型的bedpe文件
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
            t3.start();
            //合并不同linker的染色体内的交互，作为构建矩阵的输入文件
            Thread t4 = new Thread(() -> {
                try {
                    for (int i = 0; i < Chromosomes.length; i++) {
                        for (int j = 0; j < ValidLinkerSeq.length; j++) {
                            ChrBedpeFile[i].Append(LinkerChrSameCleanBedpeFile[j][i]);
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
            t4.start();
            t1.join();
            t2.join();
            t3.join();
            t4.join();
            //==============================================================================================================
        }
        if (Opts.Step.Statistic.Execute) {
            for (int i = 0; i < ValidLinkerSeq.length; i++) {
                Opts.NRStat.linkers[i].SelfLigationFile = bedpe[i].getSelfLigationFile();
                Opts.NRStat.linkers[i].ReLigationFile = bedpe[i].getReLigationFile();
                Opts.NRStat.linkers[i].DuplicateFile = bedpe[i].getRepeatFile();
                Opts.NRStat.linkers[i].SameCleanFile = bedpe[i].getSameNoDumpFile();
                Opts.NRStat.linkers[i].DiffCleanFile = bedpe[i].getDiffNoDumpFile();
                Opts.NRStat.linkers[i].CleanFile = bedpe[i].getFinalFile();
            }
            Opts.NRStat.Stat(Configure.Thread);
        }
        Opts.StatisticFile.Append(Opts.NRStat.Show() + "\n");
        //=================================================BedpeFile To Inter===========================================

        //--------------------------------------------------------------------------------------------------------------
        if (Opts.Step.BedPe2Inter.Execute) {
            new BedpeToInter(FinalBedpeFile.getPath(), InterBedpeFile.getPath());//将交互区间转换成交互点
        }
        //==============================================================================================================
        CommonFile InterDistanceDis = new CommonFile(Stat.getDataDir() + "/" + Prefix + ".all.interaction_distance_distribution.data");
        Opts.NRStat.WriteInterRangeDis(InterDistanceDis, new Region(0, Integer.MAX_VALUE), "1M", 2, 10);
        Opts.NRStat.InteractionRangeDistributionPng = new File(Stat.getImageDir() + "/" + InterDistanceDis.getName().replace(".data", ".png"));
        ComLine = Configure.Python.FullExe() + " " + Opts.StatisticPlotFile + " -t point --title Interaction_distance_distribution -i " + InterDistanceDis + " -o " + Opts.NRStat.InteractionRangeDistributionPng;
        Opts.CommandOutFile.Append(ComLine + "\n");
        new CommandLineDhat().run(ComLine, null, new PrintWriter(System.err));
        //----------------------------------
        InterDistanceDis = new CommonFile(Stat.getDataDir() + "/" + Prefix + ".50M.interaction_distance_distribution.data");
        Opts.NRStat.WriteInterRangeDis(InterDistanceDis, new Region(0, 50000000), "1M", 2, 10);
        Opts.NRStat._50M_InteractionRangeDistributionPng = new File(Stat.getImageDir() + "/" + InterDistanceDis.getName().replace(".data", ".png"));
        ComLine = Configure.Python.FullExe() + " " + Opts.StatisticPlotFile + " -t point --title Interaction_distance_distribution -i " + InterDistanceDis + " -o " + Opts.NRStat._50M_InteractionRangeDistributionPng;
        Opts.CommandOutFile.Append(ComLine + "\n");
        new CommandLineDhat().run(ComLine, null, new PrintWriter(System.err));
        //------------------------------------
        InterDistanceDis = new CommonFile(Stat.getDataDir() + "/" + Prefix + ".10M.interaction_distance_distribution.data");
        Opts.NRStat.WriteInterRangeDis(InterDistanceDis, new Region(0, 10000000), "100k", 2, 10);
        Opts.NRStat._10M_InteractionRangeDistributionPng = new File(Stat.getImageDir() + "/" + InterDistanceDis.getName().replace(".data", ".png"));
        ComLine = Configure.Python.FullExe() + " " + Opts.StatisticPlotFile + " -t point --title Interaction_distance_distribution -i " + InterDistanceDis + " -o " + Opts.NRStat._10M_InteractionRangeDistributionPng;
        Opts.CommandOutFile.Append(ComLine + "\n");
        new CommandLineDhat().run(ComLine, null, new PrintWriter(System.err));
        //---------------------------------------
        InterDistanceDis = new CommonFile(Stat.getDataDir() + "/" + Prefix + ".2M.interaction_distance_distribution.data");
        Opts.NRStat.WriteInterRangeDis(InterDistanceDis, new Region(0, 2000000), "10k", 2, 10);
        Opts.NRStat._2M_InteractionRangeDistributionPng = new File(Stat.getImageDir() + "/" + InterDistanceDis.getName().replace(".data", ".png"));
        ComLine = Configure.Python.FullExe() + " " + Opts.StatisticPlotFile + " -t point --title Interaction_distance_distribution -i " + InterDistanceDis + " -o " + Opts.NRStat._2M_InteractionRangeDistributionPng;
        Opts.CommandOutFile.Append(ComLine + "\n");
        new CommandLineDhat().run(ComLine, null, new PrintWriter(System.err));

        //---------------------------------------

        //=================================================Create Matrix==================================================
        Date matrixTime = new Date();
        System.err.println("Noise reducing: " + bedpeTime + " - " + matrixTime);
        Opts.NRStat.Time = matrixTime.getTime() - bedpeTime.getTime();
        if (Opts.Step.CreateMatrix.Execute) {
            for (Chromosome s : Chromosomes) {
                if (s.Size == 0) {
                    findenzy.start();
                    findenzy.join();
                    break;
                }
            }
            Thread[] mmt = new Thread[Resolution.length];
            for (int i = 0; i < Resolution.length; i++) {
                int finalI = i;
                mmt[i] = new Thread(() -> {
                    try {
                        MakeMatrix matrix = new MakeMatrix(new File(MakeMatrixDir + "/" + Resolution[finalI]), Prefix, new BedpeFile(FinalBedpeFile), BedpeFile.Copy(ChrBedpeFile), Chromosomes, Resolution[finalI], Threads);//生成交互矩阵类
                        matrix.Run();//运行
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
                mmt[i].start();
            }
            for (int i = 0; i < Resolution.length; i++) {
                mmt[i].join();
            }
            //------------------------------------------------------画热图-----------------------------------------
            for (int i = 0; i < DrawResolution.length; i++) {
                int aDrawResolution = DrawResolution[i];
                File OutDir = new File(MakeMatrixDir + "/img_" + Tools.UnitTrans(aDrawResolution, "B", "M") + "M");
                if (!OutDir.isDirectory() && !OutDir.mkdir()) {
                    System.err.println(new Date() + "\tWarning! Can't Create " + OutDir);
                }
                MakeMatrix matrix = new MakeMatrix(new File(MakeMatrixDir + "/" + aDrawResolution), Prefix, InterBedpeFile, ChrBedpeFile, Chromosomes, aDrawResolution, Threads);//生成交互矩阵类
                if (!new File(MakeMatrixDir + "/" + aDrawResolution).isDirectory()) {
                    matrix.Run();
                }
                Opts.CMStat.draw_resolutions[i].GenomeWildMatrixFile = matrix.getDenseMatrixFile();
                Opts.CMStat.draw_resolutions[i].GenomeWildHeatMapPng = new File(OutDir + "/" + Prefix + ".interaction_" + Tools.UnitTrans(aDrawResolution, "B", "M") + "M.png");
                //绘制全基因组热图
                Opts.CMStat.draw_resolutions[i].GenomeWildMatrixFile.PlotHeatMap(matrix.getBinSizeList(), aDrawResolution, Opts.CMStat.draw_resolutions[i].GenomeWildHeatMapPng);
                Opts.CMStat.draw_resolutions[i].ChromMatrixFile = matrix.getChrDenseMatrixFile();
                for (int j = 0; j < Chromosomes.length; j++) {
                    Opts.CMStat.draw_resolutions[i].ChromHeatMapPng[j] = new File(OutDir + "/" + Prefix + "." + Chromosomes[j].Name + "." + Tools.UnitTrans(aDrawResolution, "B", "M") + "M.png");
                    Opts.CMStat.draw_resolutions[i].ChromMatrixFile[j].PlotHeatMap(new ChrRegion(Chromosomes[j].Name, 0, 0), new ChrRegion(Chromosomes[j].Name, 0, 0), aDrawResolution / 10, 0.98f, Opts.CMStat.draw_resolutions[i].ChromHeatMapPng[j]);
                }
            }
        }
        if (Opts.Step.Statistic.Execute) {
            Opts.CMStat.Stat();
        }
        Opts.StatisticFile.Append(Opts.CMStat.Show() + "\n");
        //==============================================================================================================
        //==============================================================================================================
        Date endTime = new Date();
        System.err.println("Create matrix: " + matrixTime + " - " + endTime);
        Opts.CMStat.Time = endTime.getTime() - matrixTime.getTime();
        System.out.println("\n-------------------------------Time----------------------------------------");
        System.out.println("PreProcess:\t" + Tools.DateFormat((seTime.getTime() - preTime.getTime()) / 1000));
        System.out.println("SeProcess:\t" + Tools.DateFormat((bedpeTime.getTime() - seTime.getTime()) / 1000));
        System.out.println("BedpeProcess:\t" + Tools.DateFormat((matrixTime.getTime() - bedpeTime.getTime()) / 1000));
        System.out.println("MakeMatrix:\t" + Tools.DateFormat((endTime.getTime() - matrixTime.getTime()) / 1000));
        System.out.println("Total:\t" + Tools.DateFormat((endTime.getTime() - preTime.getTime()) / 1000));
        //===================================Component.Statistic.Report=====================================================================
        for (Thread t : SThread) {
            t.join();
        }
        Opts.StatisticFile.Append(Opts.OVStat.Show() + "\n");
        Stat.ReportHtml(new File(ReportDir + "/" + Prefix + ".report.html"));
        Tools.RemoveEmptyFile(OutPath);
        Opts.RSStat.Finish();
    }

    /**
     * Create reference genome index
     *
     * @param genomefile genome file
     */
    private synchronized void CreateIndex(File genomefile) {
        File IndexDir = new File(OutPath + "/" + Opts.OutDir.IndexDir);
        if (!IndexDir.isDirectory() && !IndexDir.mkdir()) {
            System.out.println("Create " + IndexDir + " false");
            System.exit(1);
        }
        Configure.Bwa.CreateIndex(genomefile, new File(IndexDir + "/" + genomefile.getName()), Threads);
    }

    /**
     * <p>创建酶切片段文件，获取染色体大小</p>
     *
     * @return 线程句柄
     */
    private Thread FindRestrictionFragment() {
        return new Thread(() -> {
            try {
                FragmentDigested FDM = Opts.fragmentDigestedModule;
                FDM.Threads = Threads;
                FDM.run(new FastaFile(Configure.Bwa.GenomeFile.getPath()));
                Chromosomes = FDM.getChromosomes();
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    /**
     * @return unique, multi, unmapped
     */
    private SamFile[] SeProcess(FastqFile fastqFile, String Prefix) throws IOException, InterruptedException {
        if (fastqFile.ItemNum <= 0) {
            fastqFile.ItemNum = fastqFile.getItemNum();
        }
        long splitnum = (long) Math.ceil((double) fastqFile.ItemNum / Threads * 4);
        splitnum = splitnum + (4 - splitnum % 4) % 4;
        ArrayList<CommonFile> SplitFastqFile = fastqFile.SplitFile(SeProcessDir + "/" + fastqFile.getName(), splitnum);//1亿行作为一个单位拆分
        SeProcess se = new SeProcess(fastqFile, Configure.Bwa, AlignMisMatch, MinUniqueScore, SeProcessDir, Prefix, ReadsType);
        SamFile SamFile = se.getSamFile();
        SamFile UniqSamFile = se.getUniqSamFile();
        SamFile MultiSamFile = se.getMultiSamFile();
        SamFile UnSamFile = se.getUnMapSamFile();
        BedFile SortBedFile = se.getSortBedFile();
        SamFile[] SplitSamFile = new SamFile[SplitFastqFile.size()];
        SamFile[] SplitFilterSamFile = new SamFile[SplitFastqFile.size()];
        SamFile[] SplitMultiSamFile = new SamFile[SplitFastqFile.size()];
        SamFile[] SplitUnSamFile = new SamFile[SplitFastqFile.size()];
        BedFile[] SplitSortBedFile = new BedFile[SplitFastqFile.size()];
        Thread[] t2 = new Thread[Threads];
        int[] Index = new int[]{0};
        for (int i = 0; i < t2.length; i++) {
            t2[i] = new Thread(() -> {
                int finalI;
                FastqFile InFile;
                while (Index[0] < SplitFastqFile.size()) {
                    synchronized (t2) {
                        try {
                            finalI = Index[0];
                            InFile = new FastqFile(SplitFastqFile.get(finalI));
                            Index[0]++;
                        } catch (IndexOutOfBoundsException e) {
                            break;
                        }
                    }
                    try {
                        SeProcess ssp = new SeProcess(InFile, Configure.Bwa, AlignMisMatch, MinUniqueScore, SeProcessDir, Prefix + ".split" + finalI, ReadsType);//单端处理类
                        ssp.setIteration(Iteration);
                        ssp.Run();
                        SplitSamFile[finalI] = ssp.getSamFile();
                        SplitFilterSamFile[finalI] = ssp.getUniqSamFile();
                        SplitMultiSamFile[finalI] = ssp.getMultiSamFile();
                        SplitUnSamFile[finalI] = ssp.getUnMapSamFile();
                        SplitSortBedFile[finalI] = ssp.getSortBedFile();
                    } catch (IOException | InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            });
            t2[i].start();
        }
        Tools.ThreadsWait(t2);
        for (File s : SplitFastqFile) {
            if (DeBugLevel < 1) {
                AbstractFile.delete(s);
            }
        }
        Thread t4 = new Thread(() -> {
            try {
                FileTool.MergeSamFile(SplitSamFile, SamFile);
                FileTool.MergeSamFile(SplitFilterSamFile, UniqSamFile);
                FileTool.MergeSamFile(SplitMultiSamFile, MultiSamFile);
                FileTool.MergeSamFile(SplitUnSamFile, UnSamFile);
            } catch (IOException e) {
                e.printStackTrace();
            }
            if (DeBugLevel < 1) {
                for (int i = 0; i < SplitFastqFile.size(); i++) {
                    AbstractFile.delete(SplitFastqFile.get(i));
                    AbstractFile.delete(SplitSamFile[i]);
                    AbstractFile.delete(SplitFilterSamFile[i]);
                    AbstractFile.delete(SplitMultiSamFile[i]);
                    AbstractFile.delete(SplitUnSamFile[i]);
                }
            }
        });
        t4.start();
        Thread t3 = new Thread(() -> {
            try {
                SortBedFile.MergeSortFile(SplitSortBedFile, new BedItem.TitleComparator());
                for (File s : SplitSortBedFile) {
                    if (DeBugLevel < 1) {
                        AbstractFile.delete(s);
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        t3.start();
        t3.join();
        t4.join();
        return new SamFile[]{UniqSamFile, MultiSamFile, UnSamFile};
    }

    private Thread BedpeProcess(String UseLinker, BedpeFile SeBedpeFile, int threads) {
        return new Thread(() -> {
            try {
                BedpeProcess bedpe = new BedpeProcess(new File(BedpeProcessDir + "/" + UseLinker), Prefix + "." + UseLinker, Chromosomes, SeBedpeFile);//bedpe文件处理类
                bedpe.Threads = threads;//设置线程数
                bedpe.Run();//运行

            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    //==============================

    private void Init() throws IOException {
        for (Configure.Require opt : Configure.Require.values()) {
            if (opt.Value == null || opt.Value.toString().trim().equals("")) {
                System.err.println("Error ! no " + opt);
                System.exit(1);
            }
        }
        //----------------------------------------必要参数赋值-----------------------------------------------------------
        String[] tempstrs;
        InputFile = Opts.OVStat.InputFile = Configure.InputFile;
        Configure.Bwa.GenomeFile = Opts.ALStat.GenomeFile = Configure.GenomeFile;
        //-----------------------------------------可选参数赋值----------------------------------------------------------
        Restriction = Opts.LFStat.EnzymeCuttingSite = Configure.Restriction.toString();
        if (Configure.Restriction.getSequence().length() <= 4) {
            Opts.OVStat.RangeThreshold = 5000;
        } else {
            Opts.OVStat.RangeThreshold = 20000;
        }
        Opts.NRStat.ShortRegion = new Region(0, Opts.OVStat.RangeThreshold);
        Opts.NRStat.LongRegion = new Region(Opts.OVStat.RangeThreshold, Integer.MAX_VALUE);

        String[] halfLinker = Opts.LFStat.HalfLinkers = Configure.HalfLinker;
        LinkerA = halfLinker[0];
        LinkerLength = LinkerA.length();
        if (halfLinker.length > 1) {
            LinkerB = halfLinker[1];
            LinkerLength += LinkerB.length();
        } else {
            LinkerLength += LinkerA.length();
        }
        OutPath = Opts.OVStat.OutDir = Configure.OutPath;
        Opts.CommandOutFile = new CommonFile(Configure.OutPath + "/" + Opts.CommandOutFile.getName());
        Opts.StatisticFile = new CommonFile(Configure.OutPath + "/" + Opts.StatisticFile.getName());
        Opts.ResourceStatFile = new CommonFile(Configure.OutPath + "/" + Opts.ResourceStatFile.getName());
        Prefix = Opts.OVStat.Prefix = Configure.Prefix;
        Threads = Configure.Thread;
//        Step.addAll(Arrays.asList(Configure.Step.trim().split("\\s+")));
        if (Configure.AdapterSeq != null && Configure.AdapterSeq.length > 0) {
            AdapterSeq = Configure.AdapterSeq;
        }
        Configure.Bwa.IndexPrefix = Configure.Index;
        Configure.Bwa.IndexCheck();
        Opts.Step.CreateIndex.Execute = Configure.Bwa.IndexCheck != Opts.FileFormat.Valid;
        if (Configure.Chromosome != null && Configure.Chromosome.length > 0) {
            Chromosomes = Configure.Chromosome;
        } else {
            System.out.println(new Date() + "\tCalculate chromosome ......");
            Chromosomes = Tools.CheckChromosome(Chromosomes, Configure.Bwa.GenomeFile);
            Configure.Chromosome = Chromosomes;
        }
        Resolution = Opts.CMStat.Resolutions = Configure.Resolution;
        DrawResolution = Opts.CMStat.DrawResolutions = Configure.DrawResolution;
        Opts.CMStat.Init();
        ChrEnzyFile = new BedFile[Chromosomes.length];

        //-------------------------------------------高级参数赋值--------------------------------------------------------
        MatchScore = Configure.MatchScore;
        MisMatchScore = Configure.MisMatchScore;
        InDelScore = Configure.InDelScore;
        MaxReadsLength = Opts.LFStat.MaxReadsLen = Configure.MaxReadsLen;
        AlignMisMatch = Configure.AlignMisMatch;
        Iteration = Configure.Iteration;
        ReadsType = Configure.AlignType.compareToIgnoreCase("Short") == 0 ? Opts.FileFormat.ShortReads : Configure.AlignType.compareToIgnoreCase("Long") == 0 ? Opts.FileFormat.LongReads : Opts.FileFormat.ErrorFormat;
        DeBugLevel = Configure.DeBugLevel;
        //设置唯一比对分数
        if (ReadsType == Opts.FileFormat.ShortReads) {
            MinUniqueScore = 20;
        } else if (ReadsType == Opts.FileFormat.LongReads) {
            MinUniqueScore = 30;
        } else {
            System.err.println("Error reads type " + Configure.AlignType);
        }
        Configure.MinUniqueScore = Opts.ALStat.Threshold = MinUniqueScore;
        if (Configure.MinLinkerLen == 0) {
            Configure.MinLinkerLen = (int) (LinkerLength * 0.9);
        }
        int minLinkerLength = Configure.MinLinkerLen;
        //================================================
        if (!OutPath.isDirectory()) {
            System.err.println("Error, " + OutPath + " is not a directory");
            System.exit(1);
        }
        if (!Configure.Bwa.GenomeFile.isFile()) {
            System.err.println("Error, " + Configure.Bwa.GenomeFile + " is not a file");
            System.exit(1);
        }
        if (!InputFile.isFile()) {
            System.err.println("Error, " + InputFile + " is not a file");
            System.exit(1);
        }
        //=======================================================================;
        PreProcessDir = Opts.LFStat.OutDir = new File(OutPath + "/" + Opts.OutDir.PreDir);
        SeProcessDir = Opts.ALStat.OutDir = new File(OutPath + "/" + Opts.OutDir.SeDir);
        BedpeProcessDir = Opts.NRStat.OutDir = new File(OutPath + "/" + Opts.OutDir.BedpeDir);
        MakeMatrixDir = Opts.CMStat.OutDir = new File(OutPath + "/" + Opts.OutDir.MatrixDir);
        ReportDir = new File(OutPath + "/" + Opts.OutDir.ReportDir);
        EnzyPath = new File(OutPath + "/" + Opts.OutDir.EnzyFragDir);
        File[] CheckDir = new File[]{PreProcessDir, SeProcessDir, BedpeProcessDir, MakeMatrixDir, EnzyPath, ReportDir};
        for (File s : CheckDir) {
            if (!s.isDirectory() && !s.mkdir()) {
                System.err.println("Can't create " + s);
                System.exit(1);
            }
        }
        Opts.fragmentDigestedModule = new FragmentDigested(EnzyPath, Chromosomes, new RestrictionEnzyme(Restriction), Prefix);
        tempstrs = new String[]{"A", "B", "C", "D", "E", "F", "G"};
        //构建Linker序列
        if (halfLinker.length > tempstrs.length) {
            System.err.println("Error! too many half-linker:\t" + halfLinker.length);
            System.exit(1);
        }
        LinkerSeq = new LinkerSequence[halfLinker.length * halfLinker.length];
        ValidLinkerSeq = new LinkerSequence[halfLinker.length];
        for (int i = 0; i < halfLinker.length; i++) {
            for (int j = 0; j < halfLinker.length; j++) {
                if (i == j) {
                    LinkerSeq[i * halfLinker.length + j] = new LinkerSequence(halfLinker[i] + Tools.ReverseComple(halfLinker[j]), tempstrs[i] + tempstrs[j], true);
                    ValidLinkerSeq[i] = new LinkerSequence(halfLinker[i] + Tools.ReverseComple(halfLinker[j]), tempstrs[i] + tempstrs[j], true);
                } else {
                    LinkerSeq[i * halfLinker.length + j] = new LinkerSequence(halfLinker[i] + Tools.ReverseComple(halfLinker[j]), tempstrs[i] + tempstrs[j]);
                }
            }
        }
        Opts.LFStat.Linkers = LinkerSeq;
        Opts.LFStat.Init();
        Opts.NRStat.Linkers = Opts.ALStat.Linkers = ValidLinkerSeq;
        Opts.ALStat.Init();
        Opts.NRStat.Init();
        MinLinkerFilterQuality = minLinkerLength * MatchScore + (LinkerLength - minLinkerLength) * MisMatchScore;//设置linkerfilter最小分数
        Opts.LFStat.Threshold = MinLinkerFilterQuality;
        EnzyFilePrefix = Prefix + "." + Restriction.replace("^", "");
        LinkerFile = new File(PreProcessDir + "/" + Prefix + ".linker");
        AdapterFile = new File(PreProcessDir + "/" + Prefix + ".adapter");
        //清空原来的Adapter文件
        File[] CleanFile = new File[]{LinkerFile, AdapterFile};
        for (File f : CleanFile) {
            if (!AbstractFile.clean(f)) {
                System.err.println("Error! can't clean " + f.getName());
                System.exit(1);
            }
        }
        Stat = new Report(ReportDir);
    }

    private void ShowParameter() {
        System.out.println(Configure.ShowParameter());
        System.out.println(Configure.ShowExecution());
    }


    private String[] LinkerDetection(FastqFile fastqFile) throws IOException {
        int LineNum = 100;
        String[] HalfLinkers = null;
        fastqFile.ReadOpen();
        return HalfLinkers;
    }


}
