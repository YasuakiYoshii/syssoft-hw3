import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;

public class Opfs implements Filesystem {
    /* usage: opfs img_file command [arg...]
     * command
     *     diskinfo
     *     info path
     *     ls path
     *     get path
     *     put path
     *     rm path
     *     cp spath dpath
     *     mv spath dpath
     *     ln spath dpath
     *     mkdir path
     *     rmdir path
     */

    private final int EXIT_SUCCESS = 0;
    private static final int EXIT_FAILURE = 1;

    // diskinfo
    public int do_diskinfo(MappedByteBuffer img, String args[]) {
        if (args.length != 0) {
            Libfs.error("usage: %s img_file diskinfo", Libfs.progname);
            return EXIT_FAILURE;
        }

        Superblock sb = Libfs.SBLK(img);

        int N = Libfs.SBLK(img).size;
        int Ni = sb.ninodes / IPB + 1;
        int Nm = N / (BSIZE * 8) + 1;
        int dstart = 2 + sb.nlog + Ni + Nm;
        int Nd = Libfs.SBLK(img).nblocks;

        System.out.println("total blocks: " + N + "(" + N * BSIZE + "bytes)");
        System.out.println("log blocks: #" + sb.logstart + "-#" + (sb.logstart + sb.nlog - 1) + "(" + sb.nlog + "blocks)");
        System.out.println("inode blocks: #" + sb.inodestart + "-#" + (sb.inodestart + Ni - 1) +" (" + Ni + " blocks, " + sb.ninodes +" inodes)");
        System.out.println("bitmap blocks: #" + sb.bmapstart + "-#" + (sb.bmapstart + Nm - 1) + " (" + Nm + " blocks)");
        System.out.println("data blocks: #" + dstart + "-#" + (dstart + Nd - 1) + " (" + Nd + " blocks)");
        System.out.println("maximum file size (bytes): " + Libfs.MAXFILESIZE);

        int nblocks = 0;
        for (int b = sb.bmapstart; b <= sb.bmapstart + Nm - 1; b++)
            for (int i = 0; i < BSIZE; i++)
                nblocks += bitcount(img[b][i]);
        System.out.println("# of used blocks: " + nblocks);

        int n_dirs = 0, n_files = 0, n_devs = 0;
        for (int b = sb.inodestart; b <= sb.inodestart + Ni - 1; b++)
            for (int i = 0; i < IPB; i++)
                switch (((Libfs.inode_t)img[b])[i].type) {
                    case Libfs.T_DIR:
                        n_dirs++;
                        break;
                    case Libfs.T_FILE:
                        n_files++;
                        break;
                    case Libfs.T_DEV:
                        n_devs++;
                        break;
                }
        System.out.println("# of used inodes: " + (n_dirs + n_files + n_devs) + " (dirs: " + n_dirs + ", files: " + n_files + ", devs: " + n_devs + ")");

        return EXIT_SUCCESS;
    }

    // info path
    int do_info(MappedByteBuffer img, String args[]) {
        if (args.length != 1) {
            Libfs.error("usage: %s img_file info path\n", Libfs.progname);
            return EXIT_FAILURE;
        }
        String path = args[0];

        Libfs.inode_t ip = Libfs.ilookup(img, Libfs.root_inode, path);
        if (ip == null) {
            Libfs.error("info: no such file or directory: %s", path);
            return EXIT_FAILURE;
        }
        System.out.println("inode: " + Libfs.geti(img, ip));
        System.out.println("type: " + ip.type + " (" + Libfs.typename(ip.type) + ")");
        System.out.println("nlink: " + ip.nlink);
        System.out.println("size: " + ip.size);
        if (ip.size > 0) {
            System.out.print("data blocks:");
            int bcount = 0;
            for (int i = 0; i < NDIRECT && ip.addrs[i] != 0; i++, bcount++)
                System.out.print(" " + ip.addrs[i]);
            int iaddr = ip.addrs[NDIRECT];
            if (iaddr != 0) {
                bcount++;
                System.out.print(" " + iaddr);
                int *iblock = (int *)img[iaddr];
                for (int i = 0; i < BSIZE / SIZEOFINT && iblock[i] != 0;
                     i++, bcount++)
                    System.out.print(" " + iblock[i]);
            }
            System.out.print("\n");
            System.out.println("# of data blocks: " + bcount);
        }
        return EXIT_SUCCESS;
    }

    // ls path
    int do_ls(MappedByteBuffer img, String args[]) {
        if (args.length != 1) {
            Libfs.error("usage: %s img_file ls path\n", Libfs.progname);
            return EXIT_FAILURE;
        }
        String path = args[0];
        Libfs.inode_t ip = Libfs.ilookup(img, Libfs.root_inode, path);
        if (ip == null) {
            Libfs.error("ls: %s: no such file or directory\n", path);
            return EXIT_FAILURE;
        }
        if (ip.type == Libfs.T_DIR) {
            dirent de;
            for (int off = 0; off < ip.size; off += sizeof(de)) {
                if (Libfs.iread(img, ip, (uchar *)&de, sizeof(de), off) != sizeof(de)) {
                    Libfs.error("ls: %s: read error\n", path);
                    return EXIT_FAILURE;
                }
                if (de.inum == 0)
                    continue;
                char[] name = new char[Libfs.DIRSIZ + 1];
                name[Libfs.DIRSIZ] = 0;
                strncpy(name, de.name, Libfs.DIRSIZ);
                Libfs.inode_t p = Libfs.iget(img, de.inum);
                System.out.println(name + " " +  p.type + " " + de.inum + " " + p.size);
            }
        }
        else
            System.out.println(path + " " + ip.type + " " + Libfs.geti(img, ip) + " " + ip.size);

        return EXIT_SUCCESS;
    }

    // get path
    int do_get(MappedByteBuffer img, String args[]) {
        if (args.length != 1) {
            Libfs.error("usage: %s img_file get path\n", Libfs.progname);
            return EXIT_FAILURE;
        }
        String path = args[0];

        // source
        Libfs.inode_t ip = Libfs.ilookup(img, Libfs.root_inode, path);
        if (ip == null) {
            Libfs.error("get: no such file or directory: %s\n", path);
            return EXIT_FAILURE;
        }

        char[] buf = new char[Libfs.BUFSIZE];
        for (int off = 0; off < ip.size; off += Libfs.BUFSIZE) {
            int n = Libfs.iread(img, ip, buf, Libfs.BUFSIZE, off);
            if (n < 0) {
                Libfs.error("get: %s: read error\n", path);
                return EXIT_FAILURE;
            }
            write(1, buf, n);
        }

        return EXIT_SUCCESS;
    }

    // put path
    int do_put(MappedByteBuffer img, String args[]) {
        if (args.length != 1) {
            Libfs.error("usage: %s img_file put path\n", Libfs.progname);
            return EXIT_FAILURE;
        }
        String path = args[0];

        // destination
        Libfs.inode_t ip = Libfs.ilookup(img, Libfs.root_inode, path);
        if (ip == null) {
            ip = Libfs.icreat(img, Libfs.root_inode, path, Libfs.T_FILE, null);
            if (ip == null) {
                Libfs.error("put: %s: cannot create\n", path);
                return EXIT_FAILURE;
            }
        }
        else {
            if (ip.type != Libfs.T_FILE) {
                Libfs.error("put: %s: directory or device\n", path);
                return EXIT_FAILURE;
            }
            Libfs.itruncate(img, ip, 0);
        }

        char[] buf = new char[Libfs.BUFSIZE];
        for (int off = 0; off < Libfs.MAXFILESIZE; off += Libfs.BUFSIZE) {
            int n = read(0, buf, Libfs.BUFSIZE);
            if (n < 0) {
                perror(null);
                return EXIT_FAILURE;
            }
            if (Libfs.iwrite(img, ip, buf, n, off) != n) {
                Libfs.error("put: %s: write error\n", path);
                return EXIT_FAILURE;
            }
            if (n < Libfs.BUFSIZE)
                break;
        }
        return EXIT_SUCCESS;
    }

    // rm path
    int do_rm(MappedByteBuffer img, String args[]) {
        if (args.length != 1) {
            Libfs.error("usage: %s img_file rm path\n", Libfs.progname);
            return EXIT_FAILURE;
        }
        String path = args[0];

        Libfs.inode_t ip = Libfs.ilookup(img, Libfs.root_inode, path);
        if (ip == null) {
            Libfs.error("rm: %s: no such file or directory", path);
            return EXIT_FAILURE;
        }
        if (ip.type == Libfs.T_DIR) {
            Libfs.error("rm: %s: a directory", path);
            return EXIT_FAILURE;
        }
        if (Libfs.iunlink(img, Libfs.root_inode, path) < 0) {
            Libfs.error("rm: %s: cannot unlink", path);
            return EXIT_FAILURE;
        }
        return EXIT_SUCCESS;
    }

    // cp src_path dest_path
    int do_cp(MappedByteBuffer img, String args[]) {
        if (args.length != 2) {
            Libfs.error("usage: %s img_file cp spath dpath", Libfs.progname);
            return EXIT_FAILURE;
        }
        String spath = args[0];
        String dpath = args[1];

        // source
        Libfs.inode_t sip = Libfs.ilookup(img, Libfs.root_inode, spath);
        if (sip == null) {
            Libfs.error("cp: %s: no such file or directory", spath);
            return EXIT_FAILURE;
        }
        if (sip.type != Libfs.T_FILE) {
            Libfs.error("cp: %s: directory or device file", spath);
            return EXIT_FAILURE;
        }

        // destination
        Libfs.inode_t dip = Libfs.ilookup(img, Libfs.root_inode, dpath);
        char[] ddir = new char[Libfs.BUFSIZE];
        String dname = Libfs.splitpath(dpath, ddir, Libfs.BUFSIZE);
        if (dip == null) {
            if (Libfs.is_empty(dname)) {
                Libfs.error("cp: %s: no such directory", dpath);
                return EXIT_FAILURE;
            }
            Libfs.inode_t ddip = Libfs.ilookup(img, Libfs.root_inode, ddir);
            if (ddip == null) {
                Libfs.error("cp: %s: no such directory", ddir);
                return EXIT_FAILURE;
            }
            if (ddip.type != Libfs.T_DIR) {
                Libfs.error("cp: %s: not a directory", ddir);
                return EXIT_FAILURE;
            }
            dip = Libfs.icreat(img, ddip, dname, Libfs.T_FILE, null);
            if (dip == null) {
                Libfs.error("cp: %s/%s: cannot create", ddir, dname);
                return EXIT_FAILURE;
            }
        }
        else {
            if (dip.type == Libfs.T_DIR) {
                String sname = Libfs.splitpath(spath, null, 0);
                Libfs.inode_t fp = Libfs.icreat(img, dip, sname, Libfs.T_FILE, null);
                if (fp == null) {
                    Libfs.error("cp: %s/%s: cannot create", dpath, sname);
                    return EXIT_FAILURE;
                }
                dip = fp;
            }
            else if (dip.type == Libfs.T_FILE) {
                Libfs.itruncate(img, dip, 0);
            }
            else if (dip.type == Libfs.T_DEV) {
                Libfs.error("cp: %s: device file", dpath);
                return EXIT_FAILURE;
            }
        }

        // sip : source file inode, dip : destination file inode
        char [] buf = new char[Libfs.BUFSIZE];
        for (int off = 0; off < sip.size; off += Libfs.BUFSIZE) {
            int n = Libfs.iread(img, sip, buf, Libfs.BUFSIZE, off);
            if (n < 0) {
                Libfs.error("cp: %s: read error", spath);
                return EXIT_FAILURE;
            }
            if (Libfs.iwrite(img, dip, buf, n, off) != n) {
                Libfs.error("cp: %s: write error", dpath);
                return EXIT_FAILURE;
            }
        }

        return EXIT_SUCCESS;
    }

    // mv src_path dest_path
    int do_mv(MappedByteBuffer img, String args[]) {
        if (args.length != 2) {
            Libfs.error("usage: %s img_file mv spath dpath", Libfs.progname);
            return EXIT_FAILURE;
        }
        String spath = args[0];
        String dpath = args[1];

        // source
        Libfs.inode_t sip = Libfs.ilookup(img, Libfs.root_inode, spath);
        if (sip == null) {
            Libfs.error("mv: %s: no such file or directory", spath);
            return EXIT_FAILURE;
        }
        if (sip == Libfs.root_inode) {
            Libfs.error("mv: %s: root directory", spath);
            return EXIT_FAILURE;
        }

        Libfs.inode_t dip = Libfs.ilookup(img, Libfs.root_inode, dpath);
        char [] ddir = new char[Libfs.BUFSIZE];
        String dname = Libfs.splitpath(dpath, ddir, Libfs.BUFSIZE);
        if (dip != null) {
            if (dip.type == Libfs.T_DIR) {
                String sname = Libfs.splitpath(spath, null, 0);
                Libfs.inode_t ip = Libfs.dlookup(img, dip, sname, null);
                // ip : inode of dpath/sname
                if (ip != null) {
                    if (ip.type == Libfs.T_DIR) {
                        // override existing empty directory
                        if (sip.type != Libfs.T_DIR) {
                            Libfs.error("mv: %s: not a directory", spath);
                            return EXIT_FAILURE;
                        }
                        if (!Libfs.emptydir(img, ip)) {
                            Libfs.error("mv: %s/%s: not empty", ddir, sname);
                            return EXIT_FAILURE;
                        }
                        Libfs.iunlink(img, dip, sname);
                        Libfs.daddent(img, dip, sname, sip);
                        Libfs.iunlink(img, Libfs.root_inode, spath);
                        Libfs.dmkparlink(img, dip, sip);
                        return EXIT_SUCCESS;
                    }
                    else if (ip.type == Libfs.T_FILE) {
                        // override existing file
                        if (sip.type != Libfs.T_FILE) {
                            Libfs.error("mv: %s: directory or device", spath);
                            return EXIT_FAILURE;
                        }
                        Libfs.iunlink(img, dip, sname);
                        Libfs.daddent(img, dip, sname, sip);
                        Libfs.iunlink(img, Libfs.root_inode, spath);
                        return EXIT_SUCCESS;
                    }
                    else {
                        Libfs.error("mv: %s: device", dpath);
                        return EXIT_FAILURE;
                    }
                }
                else { // ip == NULL
                    Libfs.daddent(img, dip, sname, sip);
                    Libfs.iunlink(img, Libfs.root_inode, spath);
                    if (sip.type == Libfs.T_DIR)
                        Libfs.dmkparlink(img, dip, sip);
                }
            }
            else if (dip.type == Libfs.T_FILE) {
                // override existing file
                if (sip.type != Libfs.T_FILE) {
                    Libfs.error("mv: %s: not a file", spath);
                    return EXIT_FAILURE;
                }
                Libfs.iunlink(img, Libfs.root_inode, dpath);
                Libfs.inode_t ip = ilookup(img, Libfs.root_inode, ddir);
                assert(ip != null && ip.type == Libfs.T_DIR);
                Libfs.daddent(img, ip, dname, sip);
                Libfs.iunlink(img, Libfs.root_inode, spath);
            }
            else { // dip->type == T_DEV
                Libfs.error("mv: %s: device", dpath);
                return EXIT_FAILURE;
            }
        }
        else { // dip == NULL
            if (Libfs.is_empty(dname)) {
                Libfs.error("mv: %s: no such directory", dpath);
                return EXIT_FAILURE;
            }
            Libfs.inode_t ip = Libfs.ilookup(img, Libfs.root_inode, ddir);
            if (ip == null) {
                Libfs.error("mv: %s: no such directory", ddir);
                return EXIT_FAILURE;
            }
            if (ip.type != Libfs.T_DIR) {
                Libfs.error("mv: %s: not a directory", ddir);
                return EXIT_FAILURE;
            }
            Libfs.daddent(img, ip, dname, sip);
            Libfs.iunlink(img, Libfs.root_inode, spath);
            if (sip.type == Libfs.T_DIR)
                Libfs.dmkparlink(img, ip, sip);
        }
        return EXIT_SUCCESS;
    }

    // ln src_path dest_path
    int do_ln(MappedByteBuffer img, String args[]) {
        if (args.length != 2) {
            Libfs.error("usage: %s img_file ln spath dpath", Libfs.progname);
            return EXIT_FAILURE;
        }
        String spath = args[0];
        String dpath = args[1];

        // source
        Libfs.inode_t sip = Libfs.ilookup(img, Libfs.root_inode, spath);
        if (sip == null) {
            Libfs.error("ln: %s: no such file or directory", spath);
            return EXIT_FAILURE;
        }
        if (sip.type != Libfs.T_FILE) {
            Libfs.error("ln: %s: is a directory or a device", spath);
            return EXIT_FAILURE;
        }

        // destination
        char [] ddir = new char[Libfs.BUFSIZE];
        String dname = Libfs.splitpath(dpath, ddir, Libfs.BUFSIZE);
        Libfs.inode_t dip = Libfs.ilookup(img, Libfs.root_inode, ddir);
        if (dip == null) {
            Libfs.error("ln: %s: no such directory", ddir);
            return EXIT_FAILURE;
        }
        if (dip.type != Libfs.T_DIR) {
            Libfs.error("ln: %s: not a directory", ddir);
            return EXIT_FAILURE;
        }
        if (Libfs.is_empty(dname)) {
            dname = Libfs.splitpath(spath, null, 0);
            if (Libfs.dlookup(img, dip, dname, null) != null) {
                Libfs.error("ln: %s/%s: file exists", ddir, dname);
                return EXIT_FAILURE;
            }
        }
        else {
            Libfs.inode_t ip = Libfs.dlookup(img, dip, dname, null);
            if (ip != null) {
                if (ip.type != Libfs.T_DIR) {
                    Libfs.error("ln: %s/%s: file exists", ddir, dname);
                    return EXIT_FAILURE;
                }
                dname = Libfs.splitpath(spath, null, 0);
                dip = ip;
            }
        }
        if (Libfs.daddent(img, dip, dname, sip) < 0) {
            Libfs.error("ln: %s/%s: cannot create a link", ddir, dname);
            return EXIT_FAILURE;
        }
        return EXIT_SUCCESS;
    }

    // mkdir path
    int do_mkdir(MappedByteBuffer img, String args[]) {
        if (args.length != 1) {
            Libfs.error("usage: %s img_file mkdir path", Libfs.progname);
            return EXIT_FAILURE;
        }
        String path = args[0];

        if (Libfs.ilookup(img, Libfs.root_inode, path) != null) {
            Libfs.error("mkdir: %s: file exists", path);
            return EXIT_FAILURE;
        }
        if (Libfs.icreat(img, Libfs.root_inode, path, Libfs.T_DIR, null) == null) {
            Libfs.error("mkdir: %s: cannot create", path);
            return EXIT_FAILURE;
        }
        return EXIT_SUCCESS;
    }

    // rmdir path
    int do_rmdir(MappedByteBuffer img, String args[]) {
        if (args.length != 1) {
            Libfs.error("usage: %s img_file rmdir path", Libfs.progname);
            return EXIT_FAILURE;
        }
        String path = args[0];

        Libfs.inode_t ip = Libfs.ilookup(img, Libfs.root_inode, path);
        if (ip == null) {
            Libfs.error("rmdir: %s: no such file or directory", path);
            return EXIT_FAILURE;
        }
        if (ip.type != Libfs.T_DIR) {
            Libfs.error("rmdir: %s: not a directory", path);
            return EXIT_FAILURE;
        }
        if (!Libfs.emptydir(img, ip)) {
            Libfs.error("rmdir: %s: non-empty directory", path);
            return EXIT_FAILURE;
        }
        if (Libfs.iunlink(img, Libfs.root_inode, path) < 0) {
            Libfs.error("rmdir: %s: cannot unlink", path);
            return EXIT_FAILURE;
        }
        return EXIT_SUCCESS;
    }


    abstract class cmd_table_ent {
        String name;
        String args;
        abstract int fun(MappedByteBuffer img, String[] argv);
    }

    private cmd_table_ent cmd_table[] = {
        { "diskinfo", "", do_diskinfo },
        { "info", "path", do_info },
        { "ls", "path", do_ls },
        { "get", "path", do_get },
        { "put", "path", do_put },
        { "rm", "path", do_rm },
        { "cp", "spath dpath", do_cp },
        { "mv", "spath dpath", do_mv },
        { "ln", "spath dpath", do_ln },
        { "mkdir", "path", do_mkdir },
        { "rmdir", "path", do_rmdir },
        { null, null }
    };

    static int exec_cmd(MappedByteBuffer img, String cmd, String args[]) {
        for (int i = 0; cmd_table[i].name != null; i++) {
            if (cmd.equals(cmd_table[i].name))
                return cmd_table[i].fun(img, args);
        }
        Libfs.error("unknown command: %s", cmd);
        return EXIT_FAILURE;
    }

    public static int main(String args[]) {
        Libfs.progname = args[0];
        if (args.length < 3) {
            Libfs.error("usage: %s img_file command [arg...]", Libfs.progname);
            Libfs.error("Commands are:");
            for (int i = 0; cmd_table[i].name != null; i++)
                Libfs.error("    %s %s", cmd_table[i].name, cmd_table[i].args);
            return EXIT_FAILURE;
        }
        String img_file = args[1];
        String cmd = args[2];
        File file = new File(args[1]);
        long img_size = file.length();


        /*img_t img = mmap(null, img_size, PROT_READ | PROT_WRITE,
                MAP_SHARED, img_fd, 0);*/
        try {
            // open + mmap
            RandomAccessFile img_fd = new RandomAccessFile(file, "rw");
            MappedByteBuffer img = img_fd.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, file.length());

            // get file information
            struct stat img_sbuf;
            if (fstat(img_fd, &img_sbuf) < 0) {
//                perror(img_file);
                img_fd.close();
                return EXIT_FAILURE;
            }
            Libfs.root_inode = Libfs.iget(img, Libfs.root_inode_number);

            // shift argc and argv to point the first command argument
            int status = EXIT_FAILURE;
            if (setjmp(fatal_exception_buf) == 0)
                status = exec_cmd(img, cmd, Arrays.copyOfRange(args,3,args.length));

            // Java system garbage-collect buffer itself
//            munmap(img, img_size);
            img_fd.close();

            return status;
        } catch (IOException e) {
//            perror(img_file);
            e.printStackTrace();
            return EXIT_FAILURE;
        }
    }


}
