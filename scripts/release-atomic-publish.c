#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static int is_leaf_name(const char *name) {
    return name != NULL && name[0] != '\0' && strcmp(name, ".") != 0 &&
           strcmp(name, "..") != 0 && strchr(name, '/') == NULL;
}

int main(int argc, char *argv[]) {
    int source_parent;
    int destination_parent;
    struct stat source_stat;
    struct stat destination_stat;

    if (argc != 5 || !is_leaf_name(argv[3]) || !is_leaf_name(argv[4])) {
        fprintf(stderr, "release atomic publish helper usage error\n");
        return 64;
    }

    source_parent = open(argv[1], O_RDONLY | O_DIRECTORY | O_NOFOLLOW);
    destination_parent = open(argv[2], O_RDONLY | O_DIRECTORY | O_NOFOLLOW);
    if (source_parent < 0 || destination_parent < 0 ||
        fstat(source_parent, &source_stat) != 0 ||
        fstat(destination_parent, &destination_stat) != 0 ||
        source_stat.st_dev != destination_stat.st_dev) {
        fprintf(stderr, "release atomic publish helper parent validation failed\n");
        return 65;
    }

    if (renameatx_np(source_parent, argv[3], destination_parent, argv[4],
                     RENAME_EXCL | RENAME_NOFOLLOW_ANY) != 0) {
        if (errno == EEXIST) {
            fprintf(stderr, "release atomic publish destination already exists\n");
            return 66;
        }
        fprintf(stderr, "release atomic publish rename failed\n");
        return 67;
    }

    return 0;
}
