import unittest
from boot_lab import accepted, command


class BootEvidenceTest(unittest.TestCase):
    def test_real_userspace_and_clean_shutdown_required(self):
        log = "Linux version 6.12.1\nAETHER_VM_LINUX_READY nonce=abc kernel=6.12.1 guest=aarch64 pid=1\nAETHER_VM_POWEROFF\n"
        self.assertTrue(accepted(0, log, "abc"))
        self.assertFalse(accepted(0, log, "stale_nonce"))
        self.assertFalse(accepted(-9, log, "abc"))
        self.assertFalse(accepted(0, log + "Kernel panic", "abc"))
        self.assertFalse(accepted(0, log.replace("pid=1", "pid=23"), "abc"))
        self.assertFalse(accepted(0, log.replace("AETHER_VM_POWEROFF", ""), "abc"))

    def test_lab_has_no_implicit_network_or_host_share(self):
        argv = command("qemu-system-aarch64", "/tmp/kernel image", "abc")
        self.assertEqual(argv[argv.index("-kernel") + 1], "/tmp/kernel image")
        self.assertEqual(argv[argv.index("-nic") + 1], "none")
        self.assertNotIn("-drive", argv)
        self.assertNotIn("-virtfs", argv)
        self.assertIn("tcg,thread=single", argv)


if __name__ == "__main__":
    unittest.main()
