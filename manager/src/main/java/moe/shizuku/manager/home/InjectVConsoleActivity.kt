package moe.shizuku.manager.home

import android.os.Bundle
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.databinding.StarterActivityBinding
import rikka.lifecycle.viewModels

class InjectVConsoleActivity : AppBarActivity() {

    private val viewModel by viewModels { InjectVConsoleViewModel() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_close_24)
        supportActionBar?.title = getString(R.string.home_inject_vconsole_title)

        val binding = StarterActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel.output.observe(this) {
            val output = it.data!!.trim()
            binding.text1.text = output
        }

        viewModel.start(this)
    }
}
