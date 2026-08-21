package com.phototrans.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.phototrans.R

/**
 * 品牌列表适配器 - 显示已学习的品牌
 */
class BrandAdapter : RecyclerView.Adapter<BrandAdapter.BrandViewHolder>() {

    data class BrandInfo(
        val name: String,          // 品牌名
        val sampleCount: Int,      // 样本数
        val hasMotionPhoto: Boolean,
        val hasHdr: Boolean,
        val colorRes: Int          // 品牌颜色
    )

    private val brands = mutableListOf<BrandInfo>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BrandViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_brand, parent, false)
        return BrandViewHolder(view)
    }

    override fun onBindViewHolder(holder: BrandViewHolder, position: Int) {
        holder.bind(brands[position])
    }

    override fun getItemCount(): Int = brands.size

    fun updateBrands(newBrands: List<BrandInfo>) {
        brands.clear()
        brands.addAll(newBrands)
        notifyDataSetChanged()
    }

    class BrandViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val brandIcon: ImageView = itemView.findViewById(R.id.brandIcon)
        private val brandName: TextView = itemView.findViewById(R.id.brandName)
        private val brandSamples: TextView = itemView.findViewById(R.id.brandSamples)
        private val brandFeatures: TextView = itemView.findViewById(R.id.brandFeatures)

        fun bind(info: BrandInfo) {
            brandName.text = info.name
            brandSamples.text = "${info.sampleCount} 张样本"

            val features = mutableListOf<String>()
            if (info.hasMotionPhoto) features.add("动态照片")
            if (info.hasHdr) features.add("HDR")
            brandFeatures.text = if (features.isNotEmpty()) features.joinToString(" · ") else "基础格式"

            brandIcon.setColorFilter(info.colorRes)
        }
    }
}