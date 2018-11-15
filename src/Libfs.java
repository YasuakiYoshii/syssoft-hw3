import java.nio.MappedByteBuffer;

public class Libfs implements Filesystem{
    /* img file structure
     *
     *  0    1    2           i           m           d        N-1
     * +----+----+----...----+----...----+----...----+----...----+
     * | BB | SB |    LB     |    IB     |    MB     |    DB     |
     * +----+----+----...----+----...----+----...----+----...----+
     *           |<-- Nl --->|<-- Ni --->|<-- Nm --->|<-- Nd --->|
     *
     * BB: boot block    [0, 0]
     * SB: super block   [1, 1]
     * LB: log block     [l, l + Nl - 1] (l = sb.logstart)
     * IB: inode block   [i, i + Ni - 1] (i = sb.inodestart)
     * MB: bitmap blocks [m, m + Nm - 1] (m = sb.bmapstart)
     * DB: data blocks   [d, d + Nd - 1] (d = Nb + Ns + Nl + Nm + Nb)
     * N  = sb.size = Nb + Ns + Nl + Ni + Nm + Nd
     * Nb = 1
     * Ns = 1
     * Nl = sb.nlog
     * Ni = sb.ninodes / IPB + 1
     * Nm = N / (BSIZE * 8) + 1
     * Nd = sb.nblocks
     *
     * BSIZE = 512
     * IPB = BSIZE / sizeof(struct dinode) = 512 / 64 = 8
     *
     * Example: fs.img
     * BB: boot block   [0,  0]     = [0x00000000, 0x000001ff]
     * SB: super block  [1,  1]     = [0x00000200, 0x000003ff]
     * LB: log block    [2,  31]    = [0x00000400, 0x00003fff]
     * IB: inode block  [32, 57]    = [0x00004000, 0x000073ff]
     * BB: bitmap block [58, 58]    = [0x00007400, 0x000075ff]
     * DB: data block   [59, 999]   = [0x00007600, 0x0007Cfff]
     *
     * N = 1000
     * Nl = 30
     * Ni = 200 / 8 + 1 = 26
     * Nm = 1000 / (512 * 8) + 1 = 1
     * Nd = 1000 - (1 + 1 + 30 + 26 + 1) = 941
     */

    /* dinode structure
     *
     * |<--- 32 bit ---->|
     *
     * +--------+--------+
     * |  type  |  major |  file type, major device number [ushort * 2]
     * +--------+--------+
     * |  minor |  nlink |  minor device number, # of links [ushort * 2]
     * +--------+--------+
     * |      size       |  size of the file (bytes) [uint]
     * +-----------------+  \
     * |    addrs[0]     |  |
     * +-----------------+  |
     * |       :         |   > direct data block addresses (NDIRECT=12) [uint]
     * +-----------------+  |
     * | addrs[NDIRECT-1]|  |
     * +-----------------+  /
     *
     */


    /*
     * General mathematical functions
     */


    /* fs.h */


    // Block containing inode i
    int BLOCK(int i, Superblock sb) {
        return ((i) / IPB + sb.inodestart);
    }

    // Block of free map containing bit for block b
    int BBLOCK(int b, Superblock sb) {
        return (b/BPB + sb.bmapstart);
    }

    /* libfs.h */
    static final int T_DIR = 1;
    static final int T_FILE = 2;
    static final int T_DEV = 3;

    static final int MAXFILESIZE = (MAXFILE * BSIZE);
    static final int BUFSIZE  = 1024;

    // inode
    class inode_t {
        dinode[] dinodes;
    }

    static Superblock SBLK(MappedByteBuffer img) {
        return img[1];
    }








    private static int divceil(int x, int y) {
        return (x + y - 1) / y;
    }

    // the number of 1s in a 32-bit unsigned integer
    int bitcount(int x) {
        x = x - ((x >> 1) & 0x55555555);
        x = (x & 0x33333333) + ((x >> 2) & 0x33333333);
        x = (x + (x >> 4)) & 0x0f0f0f0f;
        x = x + (x >> 8);
        x = x + (x >> 16);
        return x & 0x3f;
    }

    /*
     * Debugging and reporting functions and macros
     */

    // program name
    static String progname;
    jmp_buf fatal_exception_buf;

    static public void debug_message(final String tag, final String fmt, Object... args) {
        String str = String.format(tag + ": " + fmt, args);
        System.err.println(str);
    }

    static private void derror(final String fmt, Object... args) {
        debug_message("ERROR", fmt, args);
    }

    static private void dwarn(final String fmt, Object... args) {
        debug_message("WARNING", fmt, args);
    }

    public static void error(final String fmt, Object... args) {
        String str = String.format(fmt, args);
        System.err.println(str);
    }

    public static void fatal(final String fmt, Object... args) {
        String str = String.format("FATAL: " + fmt, args);
        System.err.println(str);
        longjmp(fatal_exception_buf, 1);
    }

    public static String typename(int type) {
        switch (type) {
            case T_DIR:
                return "directory";
            case T_FILE:
                return "file";
            case T_DEV:
                return "device";
            default:
                return "unknown";
        }
    }


    /*
     * Basic operations on blocks
     */

    // checks if b is a valid data block number
    public static boolean valid_data_block(MappedByteBuffer img, int b) {
    final int Nl = SBLK(img).nlog;                    // # of log blocks
    final int Ni = SBLK(img).ninodes / IPB + 1;       // # of inode blocks
    final int Nm = SBLK(img).size / (BSIZE * 8) + 1;  // # of bitmap blocks
    final int Nd = SBLK(img).nblocks;                 // # of data blocks
    final int d = 2 + Nl + Ni + Nm;                    // 1st data block number
        return d <= b && b <= d + Nd - 1;
    }

    // allocates a new data block and returns its block number
    static int balloc(MappedByteBuffer img) {
        for (int b = 0; b < SBLK(img).size; b += BPB) {
            String bp = img[BBLOCK(b, SBLKS(img))];
            for (int bi = 0; bi < BPB && b + bi < SBLK(img).size; bi++) {
                int m = 1 << (bi % 8);
                if ((bp[bi / 8] & m) == 0) {
                    bp[bi / 8] |= m;
                    if (!valid_data_block(img, b + bi)) {
                        fatal("balloc: " + (b + bi) + ": invalid data block number");
                        return 0; // dummy
                    }
                    memset(img[b + bi], 0, BSIZE);
                    return b + bi;
                }
            }
        }
        fatal("balloc: no free blocks");
        return 0; // dummy
    }

    // frees the block specified by b
    static int bfree(MappedByteBuffer img, int b) {
        if (!valid_data_block(img, b)) {
            derror("bfree: %u: invalid data block number", b);
            return -1;
        }
        String bp = img[BBLOCK(b, SBLKS(img))];
        int bi = b % BPB;
        int m = 1 << (bi % 8);
        if ((bp[bi / 8] & m) == 0)
            dwarn("bfree: %u: already freed block", b);
        bp[bi / 8] &= ~m;
        return 0;
    }


    /*
     * Basic operations on files (inodes)
     */

    // inode of the root directory
    static int root_inode_number = 1;
    static inode_t root_inode;

    // returns the pointer to the inum-th dinode structure
    static inode_t iget(MappedByteBuffer img, int inum) {
        if (0 < inum && inum < SBLK(img).ninodes)
        return (inode_t)img[IBLOCK(inum, SBLKS(img))] + inum % IPB;
        derror("iget: %u: invalid inode number", inum);
        return null;
    }

    // retrieves the inode number of a dinode structure
    static int geti(MappedByteBuffer img, inode_t ip) {
        int Ni = SBLK(img).ninodes / IPB + 1;       // # of inode blocks
        for (int i = 0; i < Ni; i++) {
            inode_t bp = (inode_t)img[SBLK(img).inodestart + i];
            if (bp <= ip && ip < bp + IPB)
                return ip - bp + i * IPB;
        }
        derror("geti: %p: not in the inode blocks", ip);
        return 0;
    }

    // allocate a new inode structure
    static inode_t ialloc(MappedByteBuffer img, int type) {
        for (int inum = 1; inum < SBLK(img).ninodes; inum++) {
            inode_t ip = (inode_t)img[IBLOCK(inum, SBLKS(img))] + inum % IPB;
            if (ip.type == 0) {
                memset(ip, 0, sizeof(struct dinode));
                ip.type = type;
                return ip;
            }
        }
        fatal("ialloc: cannot allocate");
        return null;
    }

    // frees inum-th inode
    static int ifree(MappedByteBuffer img, int inum) {
        inode_t ip = iget(img, inum);
        if (ip == null)
            return -1;
        if (ip.type == 0)
            dwarn("ifree: inode #%d is already freed", inum);
        if (ip.nlink > 0)
            dwarn("ifree: nlink of inode #%d is not zero", inum);
        ip.type = 0;
        return 0;
    }

    // returns n-th data block number of the file specified by ip
    static int bmap(MappedByteBuffer img, inode_t ip, int n) {
        if (n < NDIRECT) {
            int addr = ip.addrs[n];
            if (addr == 0) {
                addr = balloc(img);
                ip.addrs[n] = addr;
            }
            return addr;
        }
        else {
            int k = n - NDIRECT;
            if (k >= NINDIRECT) {
                derror("bmap: %u: invalid index number", n);
                return 0;
            }
            int iaddr = ip.addrs[NDIRECT];
            if (iaddr == 0) {
                iaddr = balloc(img);
                ip.addrs[NDIRECT] = iaddr;
            }
            int[] iblock = (int [])img[iaddr];
            if (iblock[k] == 0)
                iblock[k] = balloc(img);
            return iblock[k];
        }
    }

    // reads n byte of data from the file specified by ip
    static int iread(MappedByteBuffer img, inode_t ip, String buf, int n, int off) {
        if (ip.type == T_DEV)
            return -1;
        if (off > ip.size || off + n < off)
            return -1;
        if (off + n > ip.size)
            n = ip.size - off;
        // t : total bytes that have been read
        // m : last bytes that were read
        int t = 0;
        for (int m = 0; t < n; t += m, off += m, buf += m) {
            int b = bmap(img, ip, off / BSIZE);
            if (!valid_data_block(img, b)) {
                derror("iread: %u: invalid data block", b);
                break;
            }
            m = Math.min(n - t, BSIZE - off % BSIZE);
            memmove(buf, img[b] + off % BSIZE, m);
        }
        return t;
    }

    // writes n byte of data to the file specified by ip
    static int iwrite(MappedByteBuffer img, inode_t ip, String buf, int n, int off) {
        if (ip.type == T_DEV)
            return -1;
        if (off > ip.size || off + n < off || off + n > MAXFILESIZE)
            return -1;
        // t : total bytes that have been written
        // m : last bytes that were written
        int t = 0;
        for (int m = 0; t < n; t += m, off += m, buf += m) {
            int b = bmap(img, ip, off / BSIZE);
            if (!valid_data_block(img, b)) {
                derror("iwrite: %u: invalid data block", b);
                break;
            }
            m = Math.min(n - t, BSIZE - off % BSIZE);
            memmove(img[b] + off % BSIZE, buf, m);
        }
        if (t > 0 && off > ip.size)
            ip.size = off;
        return t;
    }

    // truncate the file specified by ip to size
    static int itruncate(MappedByteBuffer img, inode_t ip, int size) {
        if (ip.type == T_DEV)
            return -1;
        if (size > MAXFILESIZE)
            return -1;

        if (size < ip.size) {
            int n = divceil(ip.size, BSIZE);  // # of used blocks
            int k = divceil(size, BSIZE);      // # of blocks to keep
            int nd = Math.min(n, NDIRECT);          // # of used direct blocks
            int kd = Math.min(k, NDIRECT);          // # of direct blocks to keep
            for (int i = kd; i < nd; i++) {
                bfree(img, ip.addrs[i]);
                ip.addrs[i] = 0;
            }

            if (n > NDIRECT) {
                int iaddr = ip.addrs[NDIRECT];
                assert(iaddr != 0);
                int *iblock = (int *)img[iaddr];
                int ni = Math.max(n - NDIRECT, 0);  // # of used indirect blocks
                int ki = Math.max(k - NDIRECT, 0);  // # of indirect blocks to keep
                for (int i = ki; i < ni; i++) {
                    bfree(img, iblock[i]);
                    iblock[i] = 0;
                }
                if (ki == 0) {
                    bfree(img, iaddr);
                    ip.addrs[NDIRECT] = 0;
                }
            }
        }
        else {
            int n = size - ip.size; // # of bytes to be filled
            for (int off = ip.size, t = 0, m = 0; t < n; t += m, off += m) {
                String bp = img[bmap(img, ip, off / BSIZE)];
                m = Math.min(n - t, BSIZE - off % BSIZE);
                memset(bp + off % BSIZE, 0, m);
            }
        }
        ip.size = size;
        return 0;
    }


    /*
     * Pathname handling functions
     */

    // check if s is an empty string
    static boolean is_empty(String s) {
        return s.equals("");
    }

    // check if c is a path separator
    static boolean is_sep(char c) {
        return c == '/';
    }

// adapted from skipelem in xv6/fs.c
    static String skipelem(char[] path, String name) {
        int i=0;
        while (is_sep(path[0]))
        i++;
        String s = path;
        while (!is_empty(path) && !is_sep(*path))
        path++;
        int len = Math.min(path - s, DIRSIZ);
        memmove(name, s, len);
        if (len < DIRSIZ)
            name[len] = 0;
        return path;
    }

// split the path into directory name and base name
    static String splitpath(String path, String dirbuf, int size) {
        String s = path, t = path;
        while (!is_empty(path)) {
            while (is_sep(*path))
            path++;
            s = path;
            while (!is_empty(path) && !is_sep(*path))
            path++;
        }
        if (dirbuf != null) {
            int n = Math.min(s - t, size - 1);
            memmove(dirbuf, t, n);
            dirbuf[n] = 0;
        }
        return s;
    }

    /*
     * Operations on directories
     */

    // search a file (name) in a directory (dp)
    static inode_t dlookup(MappedByteBuffer img, inode_t dp, String name, int offp) {
        assert(dp.type == T_DIR);
        dirent de;
        for (int off = 0; off < dp.size; off += sizeof(de)) {
            if (iread(img, dp, (uchar *)&de, sizeof(de), off) != sizeof(de)) {
                derror("dlookup: %s: read error", name);
                return null;
            }
            if (strncmp(name, de.name, DIRSIZ) == 0) {
//                if (offp != null)
                offp = off;
                return iget(img, de.inum);
            }
        }
        return null;
    }

    // add a new directory entry in dp
    static int daddent(MappedByteBuffer img, inode_t dp, String name, inode_t ip) {
        dirent de;
        int off;
        // try to find an empty entry
        for (off = 0; off < dp.size; off += sizeof(de)) {
            if (iread(img, dp, (char *)&de, sizeof(de), off) != sizeof(de)) {
                derror("daddent: %u: read error", geti(img, dp));
                return -1;
            }
            if (de.inum == 0)
                break;
            if (strncmp(de.name, name, DIRSIZ) == 0) {
                derror("daddent: %s: exists", name);
                return -1;
            }
        }
        strncpy(de.name, name, DIRSIZ);
        de.inum = (short)geti(img, ip);
        if (iwrite(img, dp, (uchar *)&de, sizeof(de), off) != sizeof(de)) {
            derror("daddent: %u: write error", geti(img, dp));
            return -1;
        }
        if (strncmp(name, ".", DIRSIZ) != 0)
            ip.nlink++;
        return 0;
    }

    // create a link to the parent directory
    static int dmkparlink(MappedByteBuffer img, inode_t pip, inode_t cip) {
        if (pip.type != T_DIR) {
            derror("dmkparlink: %d: not a directory", geti(img, pip));
            return -1;
        }
        if (cip.type != T_DIR) {
            derror("dmkparlink: %d: not a directory", geti(img, cip));
            return -1;
        }
        int off;
        dlookup(img, cip, "..", &off);
        dirent de;
        de.inum = (short)geti(img, pip);
        strncpy(de.name, "..", DIRSIZ);
        if (iwrite(img, cip, (uchar *)&de, sizeof(de), off) != sizeof(de)) {
            derror("dmkparlink: write error");
            return -1;
        }
        pip.nlink++;
        return 0;
    }


    // returns the inode number of a file (rp/path)
    static inode_t ilookup(MappedByteBuffer img, inode_t rp, String path) {
        char[] name = new char[DIRSIZ + 1];
        name[DIRSIZ] = 0;
        while (true) {
            assert(path != null && rp != null && rp.type == T_DIR);
            path = skipelem(path, name);
            // if path is empty (or a sequence of path separators),
            // it should specify the root direcotry (rp) itself
            if (is_empty(name))
                return rp;

            inode_t ip = dlookup(img, rp, name, null);
            if (ip == null)
                return null;
            if (is_empty(path))
                return ip;
            if (ip.type != T_DIR) {
                derror("ilookup: %s: not a directory", name);
                return null;
            }
            rp = ip;
        }
    }

    // create a file
    static inode_t icreat(MappedByteBuffer img, inode_t rp, String path, int type, inode_t[] dpp) {
        char[] name = new char[DIRSIZ + 1];
        name[DIRSIZ] = 0;
        while (true) {
            assert(path != null && rp != null && rp.type == T_DIR);
            path = skipelem(path, name);
            if (is_empty(name)) {
                derror("icreat: %s: empty file name", path);
                return null;
            }

            inode_t ip = dlookup(img, rp, name, null);
            if (is_empty(path)) {
                if (ip != null) {
                    derror("icreat: %s: file exists", name);
                    return null;
                }
                ip = ialloc(img, type);
                daddent(img, rp, name, ip);
                if (ip.type == T_DIR) {
                    daddent(img, ip, ".", ip);
                    daddent(img, ip, "..", rp);
                }
                if (dpp != null)
                *dpp = rp;
                return ip;
            }
            if (ip == null || ip->type != T_DIR) {
                derror("icreat: %s: no such directory", name);
                return null;
            }
            rp = ip;
        }
    }

    // checks if dp is an empty directory
    static boolean emptydir(MappedByteBuffer img, inode_t dp) {
        int nent = 0;
        dirent de;
        for (int off = 0; off < dp.size; off += sizeof(de)) {
            iread(img, dp, (char *)&de, sizeof(de), off);
            if (de.inum != 0)
                nent++;
        }
        return nent == 2;
    }

    // unlinks a file (dp/path)
    static int iunlink(MappedByteBuffer img, inode_t rp, String path) {
        char[] name = new char[DIRSIZ + 1];
        name[DIRSIZ] = 0;
        while (true) {
            assert(path != null && rp != null && rp.type == T_DIR);
            path = skipelem(path, name);
            if (is_empty(name)) {
                derror("iunlink: empty file name");
                return -1;
            }
            int off;
            inode_t ip = dlookup(img, rp, name, &off);
            if (ip != null && is_empty(path)) {
                if (strncmp(name, ".", DIRSIZ) == 0 ||
                        strncmp(name, "..", DIRSIZ) == 0) {
                    derror("iunlink: cannot unlink \".\" or \"..\"");
                    return -1;
                }
                // erase the directory entry
                char zero = new char[sizeof(dirent)];
                memset(zero, 0, sizeof(zero));
                if (iwrite(img, rp, zero, sizeof(zero), off) != sizeof(zero)) {
                    derror("iunlink: write error");
                    return -1;
                }
                if (ip.type == T_DIR && dlookup(img, ip, "..", null) == rp)
                    rp.nlink--;
                ip.nlink--;
                if (ip.nlink == 0) {
                    if (ip.type != T_DEV)
                        itruncate(img, ip, 0);
                    ifree(img, geti(img, ip));
                }
                return 0;
            }
            if (ip == null || ip.type != T_DIR) {
                derror("iunlink: %s: no such directory", name);
                return -1;
            }
            rp = ip;
        }
    }


}
