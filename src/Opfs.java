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

    public int do_diskinfo(img_t img, int argc, String argv[]) {
        if (argc != 0) {
            error("usage: " + progname + " img_file diskinfo");
            return EXIT_FAILURE;
        }

        Superblock sb = SBLK(img);

        int N = SBLK(img).size;
        int Ni = sb.ninodes / IPB + 1;
        int Nm = N / (BSIZE * 8) + 1);
        int dstart = 2 + sb.nlog + Ni + Nm;
        int Nd = SBLK(img).nblocks;

        System.out.println("total blocks: " + N + "(" + N * BSIZE + "bytes)");
        System.out.println("log blocks: #" + sb.logstart + "-#" + (sb.logstart + sb.nlog - 1) + "(" + sb.nlog + "blocks)");
        System.out.println("inode blocks: #" + sb.inodestart + "-#" + (sb.inodestart + Ni - 1) +" (" + Ni + " blocks, " + sb.ninodes +" inodes)");
        System.out.println("bitmap blocks: #" + sb.bmapstart + "-#" + (sb.bmapstart + Nm - 1) + " (" + Nm + " blocks)");
        System.out.println("data blocks: #" + dstart + "-#" + (dstart + Nd - 1) + " (" + Nd + " blocks)");
        System.out.println("maximum file size (bytes): " + MAXFILESIZE);

        int nblocks = 0;
        for (int b = sb.bmapstart; b <= sb.bmapstart + Nm - 1; b++)
            for (int i = 0; i < BSIZE; i++)
                nblocks += bitcount(img[b][i]);
        System.out.println("# of used blocks: " + nblocks);

        int n_dirs = 0, n_files = 0, n_devs = 0;
        for (int b = sb.inodestart; b <= sb.inodestart + Ni - 1; b++)
            for (int i = 0; i < IPB; i++)
                switch (((inode_t)img[b])[i].type) {
                    case T_DIR:
                        n_dirs++;
                        break;
                    case T_FILE:
                        n_files++;
                        break;
                    case T_DEV:
                        n_devs++;
                        break;
                }
        System.out.println("# of used inodes: " + (n_dirs + n_files + n_devs) + " (dirs: " + n_dirs + ", files: " + n_files + ", devs: " + n_devs + ")");

        return EXIT_SUCCESS;
    }
}
