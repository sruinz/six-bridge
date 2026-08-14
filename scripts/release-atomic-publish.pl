#!/usr/bin/perl
use strict;
use warnings;
use Digest::SHA;
use Fcntl qw(O_DIRECTORY O_RDONLY O_NOFOLLOW);

use constant O_CLOEXEC => 0x01000000;

my @files = (
    'SIX-Bridge-Manager-0.1.1-android.apk',
    'SIX-Bridge-0.1.1-macos-unsigned.dmg',
    'SHA256SUMS',
    'RELEASE-METADATA.json',
    'RELEASE-NOTES-KO.md',
);

sub fail { print STDERR "release atomic publish verification failed\n"; exit 66; }
sub leaf { defined $_[0] && $_[0] ne '' && $_[0] !~ m{/} && $_[0] ne '.' && $_[0] ne '..' }
sub parse_identity {
    my ($value) = @_;
    $value =~ /\A(\d+):(\d+):(\d+):(\d+):([0-9a-f]{64})\z/ or fail();
    return ($1, $2, $3, $4, $5);
}
sub openat_fd {
    my ($directory_fd, $name, $flags) = @_;
    my $openat = 463;
    my $parent_argument = $directory_fd;
    my $name_argument = $name;
    my $flags_argument = $flags;
    my $mode_argument = 0;
    my $file_fd = syscall($openat, $parent_argument, $name_argument,
        $flags_argument, $mode_argument);
    $file_fd >= 0 or fail();
    return $file_fd;
}
sub open_file {
    my ($dirfd, $name) = @_;
    my $file_fd = openat_fd($dirfd, $name, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    open(my $file, "<&=$file_fd") or fail();
    return $file;
}
sub verify_file {
    my ($dirfd, $name, $identity) = @_;
    my ($device, $inode, $size, $links, $digest) = parse_identity($identity);
    my $file = open_file($dirfd, $name);
    my @stat = stat($file);
    @stat && -f _ && $stat[0] == $device && $stat[1] == $inode &&
        $stat[7] == $size && $stat[3] == $links && $links == 1 or fail();
    my $hasher = Digest::SHA->new(256);
    $hasher->addfile($file) or fail();
    my $actual = $hasher->hexdigest();
    close $file or fail();
    $actual eq $digest or fail();
    return $actual;
}

@ARGV == 11 or fail();
my ($source_parent, $destination_parent, $source_leaf, $destination_leaf,
    $directory_identity, $container_identity, @identities) = @ARGV;
leaf($source_leaf) && leaf($destination_leaf) or fail();
opendir(my $source_parent_handle, $source_parent) or fail();
opendir(my $destination_parent_handle, $destination_parent) or fail();
my $source_parent_fd = fileno($source_parent_handle);
my $destination_parent_fd = fileno($destination_parent_handle);
my @source_parent_stat = stat($source_parent_handle);
my @destination_parent_stat = stat($destination_parent_handle);
@source_parent_stat && @destination_parent_stat &&
    $source_parent_stat[0] == $destination_parent_stat[0] &&
    $source_parent_stat[1] == $destination_parent_stat[1] or fail();
$container_identity =~ /\A(\d+):(\d+):/ or fail();
$source_parent_stat[0] == $1 && $source_parent_stat[1] == $2 &&
    $destination_parent_stat[0] == $1 && $destination_parent_stat[1] == $2 or fail();

$directory_identity =~ /\A(\d+):(\d+):/ or fail();
my ($expected_source_device, $expected_source_inode) = ($1, $2);
my $source_fd = openat_fd($source_parent_fd, $source_leaf,
    O_RDONLY | O_DIRECTORY | O_NOFOLLOW);
open(my $source_file, "<&=$source_fd") or fail();
my @source_stat = stat($source_file);
@source_stat && -d _ && $source_stat[0] == $expected_source_device &&
    $source_stat[1] == $expected_source_inode or fail();
my $source_path = "\0" x 1024;
fcntl($source_file, 50, $source_path) or fail();
$source_path =~ s/\0.*\z//s;
length $source_path or fail();
opendir(my $source_handle, $source_path) or fail();
my @enumeration_stat = stat($source_handle);
@enumeration_stat && -d _ && $enumeration_stat[0] == $source_stat[0] &&
    $enumeration_stat[1] == $source_stat[1] or fail();
my %expected = map { $_ => 1 } @files;
my @entries = grep { $_ ne '.' && $_ ne '..' } readdir($source_handle);
@entries == 5 && !grep { !$expected{$_} } @entries or fail();
my @digests = map { verify_file($source_fd, $files[$_], $identities[$_]) } 0 .. 4;
my $expected_sums = "$digests[0]  $files[0]\n$digests[1]  $files[1]\n" .
    "$digests[3]  $files[3]\n$digests[4]  $files[4]\n";
my $sums = open_file($source_fd, $files[2]);
my $actual_sums = do { local $/; <$sums> };
close $sums or fail();
defined $actual_sums && $actual_sums eq $expected_sums or fail();

my $fresh_source_fd = openat_fd($source_parent_fd, $source_leaf,
    O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
open(my $fresh_source, "<&=$fresh_source_fd") or fail();
my @fresh_source_stat = stat($fresh_source);
@fresh_source_stat && -d _ &&
    $fresh_source_stat[0] == $source_stat[0] &&
    $fresh_source_stat[1] == $source_stat[1] or fail();

my $renameatx_np = 488;
my $rename_flags = 0x14;
my $source_fd_argument = $source_parent_fd;
my $destination_fd_argument = $destination_parent_fd;
my $source_name_argument = $source_leaf;
my $destination_name_argument = $destination_leaf;
syscall($renameatx_np, $source_fd_argument, $source_name_argument,
    $destination_fd_argument, $destination_name_argument, $rename_flags) == 0 or do {
    print STDERR "release atomic publish rename failed\n";
    exit 67;
};
close $fresh_source or fail();
exit 0;
