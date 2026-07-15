package com.example.synergic_pos_offline.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.synergic_pos_offline.R

class AdminDashboardFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_admin_dashboard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<Button>(R.id.btnCreateUser).setOnClickListener {
            // Implementation for creating general users
            Toast.makeText(requireContext(), "Opening Create User Screen...", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<Button>(R.id.btnManageUsers).setOnClickListener {
            // Implementation for blocking/resetting users
            Toast.makeText(requireContext(), "Opening User Management...", Toast.LENGTH_SHORT).show()
        }
    }
}